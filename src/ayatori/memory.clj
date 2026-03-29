(ns ayatori.memory)

(defprotocol IMemory
  (add-message [this msg] "Adds a message to memory. Returns this.")
  (get-messages [this] "Returns the current message list.")
  (clear! [this] "Clears all messages. Returns this."))

(defrecord SlidingWindowMemory [messages max-messages]
  IMemory
  (add-message [this msg]
    (swap! messages #(vec (take-last max-messages (conj % msg))))
    this)
  (get-messages [_] @messages)
  (clear! [this] (reset! messages []) this))

(def MemoryConfig
  [:map
   [:type [:enum :sliding-window]]
   [:max-messages {:optional true} :int]])

(defn make-memory
  {:malli/schema [:=> [:cat MemoryConfig] :any]}
  [{:keys [type max-messages] :or {max-messages 50}}]
  (case type
    :sliding-window (->SlidingWindowMemory (atom []) max-messages)
    (throw (ex-info "Unknown memory type" {:type type}))))
