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

(defn- partition-by-system
  "Separates system messages from others. Returns {:system [...] :other [...]}."
  [messages]
  (let [{system true other false} (group-by #(= :system (:role %)) messages)]
    {:system (or system []) :other (or other [])}))

(defn- trim-to-budget
  "Trims messages from front until total tokens <= budget. Uses cumulative sums."
  [msgs count-fn budget]
  (let [tokens (mapv count-fn msgs)
        total (reduce + 0 tokens)]
    (if (<= total budget)
      msgs
      (let [excess (- total budget)]
        (loop [dropped 0 idx 0]
          (if (or (>= idx (dec (count msgs)))
                  (>= dropped excess))
            (vec (drop idx msgs))
            (recur (+ dropped (nth tokens idx)) (inc idx))))))))

(defn- sliding-window-strategy
  [messages _msg {:keys [max-messages preserve-system] :or {max-messages 50}}]
  (if preserve-system
    (let [{:keys [system other]} (partition-by-system messages)
          keep-count (- max-messages (count system))]
      (into system (vec (take-last keep-count other))))
    (vec (take-last max-messages messages))))

(defn- token-budget-strategy
  [messages _msg {:keys [max-tokens count-fn preserve-system]
                  :or {max-tokens 4000 preserve-system true}}]
  (let [count-fn (or count-fn default-token-count)]
    (if preserve-system
      (let [{:keys [system other]} (partition-by-system messages)
            system-tokens (reduce + 0 (map count-fn system))
            budget (- max-tokens system-tokens)]
        (into system (trim-to-budget other count-fn budget)))
      (trim-to-budget messages count-fn max-tokens))))

(defn- summary?
  "Returns true if message content starts with [Summary]."
  [msg]
  (some-> msg :content (str/starts-with? "[Summary]")))

(defn- summary-strategy
  [messages _msg {:keys [threshold keep-recent llm prompt invoke-fn]
                  :or {threshold 20 keep-recent 5
                       prompt "Summarize this conversation concisely:"}}]
  (async/go
    (if (< (count messages) threshold)
      messages
      (let [{:keys [system other]} (partition-by-system messages)
            original-system (filterv (complement summary?) system)
            non-summary-msgs (filterv (complement summary?) other)
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
