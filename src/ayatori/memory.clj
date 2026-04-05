(ns ayatori.memory)

(defprotocol IMemory
  (add-message [this msg] "Adds a message to memory. Returns this.")
  (get-messages [this] "Returns the current message list.")
  (clear! [this] "Clears all messages. Returns this."))

(defn- sliding-window-strategy
  "Keeps last N messages. Preserves system role messages when :preserve-system true."
  [messages _msg {:keys [max-messages preserve-system] :or {max-messages 50}}]
  (if preserve-system
    (let [system-msgs (filterv #(= :system (:role %)) messages)
          other-msgs (filterv #(not= :system (:role %)) messages)
          keep-count (- max-messages (count system-msgs))
          kept (vec (take-last keep-count other-msgs))]
      (into system-msgs kept))
    (vec (take-last max-messages messages))))

(def ^:private strategies
  {:sliding-window sliding-window-strategy})

(defn- resolve-strategy [strategy-config]
  (let [strategy-fn (get strategies (:type strategy-config))]
    (when-not strategy-fn
      (throw (ex-info "Unknown strategy" {:type (:type strategy-config)})))
    strategy-fn))

(defrecord Memory [messages strategies-config]
  IMemory
  (add-message [this msg]
    (swap! messages
           (fn [msgs]
             (reduce (fn [m cfg]
                       (let [strategy-fn (resolve-strategy cfg)]
                         (strategy-fn m msg cfg)))
                     (conj msgs msg)
                     strategies-config)))
    this)
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
