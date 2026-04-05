(ns ayatori.memory
  (:require
   [ayatori.llm.http :as http]
   [ayatori.llm.provider :as provider]
   [clojure.core.async :as async]
   [clojure.string :as str]))

(defprotocol IMemory
  (add-message [this msg] "Adds a message to memory. Returns channel yielding this.")
  (get-messages [this] "Returns the current message list.")
  (clear! [this] "Clears all messages. Returns this."))

(defn- default-token-count [msg]
  (max 1 (quot (count (or (:content msg) "")) 4)))

(defn- sliding-window-strategy
  [messages _msg {:keys [max-messages preserve-system] :or {max-messages 50}}]
  (if preserve-system
    (let [system-msgs (filterv #(= :system (:role %)) messages)
          other-msgs (filterv #(not= :system (:role %)) messages)
          keep-count (- max-messages (count system-msgs))
          kept (vec (take-last keep-count other-msgs))]
      (into system-msgs kept))
    (vec (take-last max-messages messages))))

(defn- token-budget-strategy
  [messages _msg {:keys [max-tokens count-fn preserve-system]
                  :or {max-tokens 4000 preserve-system true}}]
  (let [count-fn (or count-fn default-token-count)]
    (if preserve-system
      (let [system-msgs (filterv #(= :system (:role %)) messages)
            other-msgs (filterv #(not= :system (:role %)) messages)
            system-tokens (reduce + 0 (map count-fn system-msgs))
            budget (- max-tokens system-tokens)]
        (loop [msgs other-msgs]
          (if (or (<= (count msgs) 1)
                  (<= (reduce + 0 (map count-fn msgs)) budget))
            (into system-msgs msgs)
            (recur (vec (rest msgs))))))
      (loop [msgs messages]
        (if (or (<= (count msgs) 1)
                (<= (reduce + 0 (map count-fn msgs)) max-tokens))
          msgs
          (recur (vec (rest msgs))))))))

(defn- summary-strategy
  [messages _msg {:keys [threshold keep-recent llm prompt invoke-fn]
                  :or {threshold 20 keep-recent 5
                       prompt "Summarize this conversation concisely:"}}]
  (async/go
    (if (< (count messages) threshold)
      messages
      (let [;; Keep only non-summary system messages (like initial prompt)
            original-system (filterv #(and (= :system (:role %))
                                           (not (str/starts-with? (or (:content %) "") "[Summary]")))
                                     messages)
            other-msgs (filterv #(or (not= :system (:role %))
                                     (str/starts-with? (or (:content %) "") "[Summary]"))
                                messages)
            ;; Filter out old summaries from other-msgs for summarization
            non-summary-msgs (filterv #(not (str/starts-with? (or (:content %) "") "[Summary]")) other-msgs)
            to-summarize (vec (drop-last keep-recent non-summary-msgs))
            to-keep (vec (take-last keep-recent non-summary-msgs))]
        (if (empty? to-summarize)
          messages
          (let [conversation (str/join "\n" (map #(str (name (:role %)) ": " (:content %)) to-summarize))
                summary-prompt (str prompt "\n\n" conversation)
                response (if invoke-fn
                           (async/<! (invoke-fn llm {:messages [{:role :user :content summary-prompt}]}))
                           (let [{:keys [url body]} (provider/build-request llm {:messages [{:role :user :content summary-prompt}]})]
                             (async/<! (http/async-post url body))))]
            (if (instance? Throwable response)
              messages
              (let [parsed (if invoke-fn response (provider/parse-response llm {} response))
                    summary-content (:content parsed)]
                (into original-system (cons {:role :system :content (str "[Summary] " summary-content)} to-keep))))))))))

(def ^:private strategies
  {:sliding-window {:fn sliding-window-strategy :async false}
   :token-budget {:fn token-budget-strategy :async false}
   :summary {:fn summary-strategy :async true}})

(defn- resolve-strategy [strategy-config]
  (let [entry (get strategies (:type strategy-config))]
    (when-not entry
      (throw (ex-info "Unknown strategy" {:type (:type strategy-config)})))
    entry))

(defrecord Memory [messages strategies-config]
  IMemory
  (add-message [this msg]
    (async/go
      (loop [msgs (conj @messages msg)
             cfgs strategies-config]
        (if (empty? cfgs)
          (do (reset! messages msgs) this)
          (let [cfg (first cfgs)
                {:keys [fn async]} (resolve-strategy cfg)
                result (if async
                         (async/<! (fn msgs msg cfg))
                         (fn msgs msg cfg))]
            (recur result (rest cfgs)))))))
  (get-messages [_] @messages)
  (clear! [this] (reset! messages []) this))

(def MemoryConfig
  [:map
   [:strategies {:optional true}
    [:vector [:map
              [:type :keyword]]]]])

(def default-strategies
  [{:type :sliding-window :max-messages 50 :preserve-system true}])

(defn make-memory
  "Creates a Memory instance with specified strategies."
  ([] (make-memory {}))
  ([{:keys [strategies]}]
   (let [strategies (or strategies default-strategies)]
     (->Memory (atom []) strategies))))
