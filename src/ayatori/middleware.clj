(ns ayatori.middleware)

(defprotocol IGraphMiddleware
  (on-graph-start [this ctx])
  (on-graph-end   [this ctx])
  (on-graph-error [this ctx])
  (on-node-start  [this ctx])
  (on-node-end    [this ctx]))

(defrecord TapMiddleware []
  IGraphMiddleware
  (on-graph-start [_ ctx] (tap> (assoc ctx :type :graph/start)))
  (on-graph-end   [_ ctx] (tap> (assoc ctx :type :graph/end)))
  (on-graph-error [_ ctx] (tap> (assoc ctx :type :graph/error)))
  (on-node-start  [_ ctx] (tap> (assoc ctx :type :node/start)))
  (on-node-end    [_ ctx] (tap> (assoc ctx :type :node/end))))

(defn make-tap
  "Creates a TapMiddleware that emits events via tap>."
  []
  (->TapMiddleware))
