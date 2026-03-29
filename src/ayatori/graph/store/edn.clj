(ns ayatori.graph.store.edn
  (:require
   [ayatori.graph.store :as store]
   [clojure.edn :as edn]
   [clojure.java.io :as io]))

(defn- read-data [path]
  (let [f (io/file path)]
    (if (.exists f)
      (edn/read-string (slurp f))
      {})))

(defn- write-data [path data]
  (let [f (io/file path)
        tmp (io/file (str path ".tmp"))]
    (io/make-parents f)
    (spit tmp (pr-str data))
    (.renameTo tmp f)))

(defrecord EdnStore [path]
  store/IStateStore
  (get-exec [_ eid]
    (get-in (read-data path) [:execs eid]))
  (save-exec! [_ eid s]
    (write-data path (assoc-in (read-data path) [:execs eid] s))
    nil)
  (delete-exec! [_ eid]
    (write-data path
                (let [d (read-data path)]
                  (-> d (update :execs dissoc eid) (update :nodes dissoc eid))))
    nil)
  (get-node-state [_ eid nid]
    (get-in (read-data path) [:nodes eid nid]))
  (save-node-state! [_ eid nid s]
    (write-data path (assoc-in (read-data path) [:nodes eid nid] s))
    nil))
