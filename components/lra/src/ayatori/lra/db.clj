(ns ayatori.lra.db
  (:require
   [datascript.core :as d]
   [malli.core :as m]
   [ayatori.lra.domain :as domain]))

(def DS
  ;; check an instance of for now
  [:fn (fn [v] (instance? clojure.lang.Atom v))])

(def TransactResult
  ;; just a simple definition we dont need the detail of it
  [:map
   [:db-before any?]
   [:db-after any?]
   [:tx-data [:sequential any?]]
   [:tempids [:map-of any? any?]]
   [:tx-meta any?]])

(m/=> save! [:=> [:cat DS domain/LRA] domain/LRA])
(defn save!
  [ds lra]
  (d/transact! ds [lra])
  lra)

(m/=> all-by-status [:=> [:cat DS domain/LRAStatus] [:vector {:min 0} domain/LRA]])
(defn all-by-status
  [ds status]
  (->> (d/q '[:find (pull ?e [*])
              :in $ ?status
              :where [?e :lra/status ?status]]
            @ds status)
       flatten
       vec))

(m/=> find-by-code [:=> [:cat DS domain/LRACode] [:or domain/LRA nil?]])
(defn find-by-code
  [ds code]
  (d/pull @ds '[*] [:lra/code code]))

(m/=> set-status! [:=> [:cat DS domain/LRACode domain/LRAStatus] TransactResult])
(defn set-status!
  [ds code status]
  (d/transact! ds [{:db/id [:lra/code code] :lra/status status}]))
