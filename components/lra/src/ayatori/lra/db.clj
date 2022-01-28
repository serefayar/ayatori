(ns ayatori.lra.db
  (:require [datascript.core :as d]))

(defn save!
  [ds lra]
  (-> (d/transact! ds [lra])
      :tempids
      vals
      first))

(defn all-by-status
  [ds status]
  (->> (d/q '[:find (pull ?e [*])
              :in $ ?status
              :where [?e :lra/status ?status]]
            @ds status)
       flatten
       vec))

(defn find-by-code
  [ds code]
  (d/pull @ds '[*] [:lra/code code]))

(defn find-by-id
  [ds id]
  (d/pull @ds '[*] id))

(defn set-status!
  [ds code status]
  (d/transact! ds [{:db/id [:lra/code code] :lra/status status}]))
