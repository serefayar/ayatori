(ns ayatori.graph.store)

(defprotocol IStateStore
  (get-exec [store exec-id])
  (save-exec! [store exec-id state])
  (delete-exec! [store exec-id])
  (get-node-state [store exec-id node-id])
  (save-node-state! [store exec-id node-id state]))

(defrecord AtomStore [data]
  IStateStore
  (get-exec [_ eid] (get-in @data [:execs eid]))
  (save-exec! [_ eid s] (swap! data assoc-in [:execs eid] s) nil)
  (delete-exec! [_ eid]
    (swap! data #(-> % (update :execs dissoc eid) (update :nodes dissoc eid)))
    nil)
  (get-node-state [_ eid nid] (get-in @data [:nodes eid nid]))
  (save-node-state! [_ eid nid s] (swap! data assoc-in [:nodes eid nid] s) nil))

(defn make-store
  "Creates a state store from config. Default: in-memory AtomStore.
   Supports :atom (default) and :edn (file-based persistence)."
  ([] (make-store {:type :atom}))
  ([{:keys [type] :as config}]
   (case type
     :atom (->AtomStore (atom {}))
     :edn  (do (require 'ayatori.graph.store.edn)
               ((resolve 'ayatori.graph.store.edn/->EdnStore) (:path config)))
     (throw (ex-info "Unknown store type" {:type type :supported [:atom :edn]})))))
