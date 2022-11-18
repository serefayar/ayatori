(ns ayatori.lra.db
  (:require
   [datascript.core :as d]
   [malli.core :as m]
   [ayatori.lra-domain.interface :as domain]
   [exoscale.ex :as ex]
   [ayatori.database.interface :as db])
  (:import (clojure.lang Atom)))

(ex/derive ::generic-db-error ::ex/fault)

(def DatabaseComponent
  ;; check an instance of for now
  db/DatabaseComponent)

(def DS
  ;; check an instance of for now
  [:fn (fn [v] (instance? Atom v))])

(defn uuid
  []
  (str (d/squuid)))

(def TransactResult
  ;; just a simple definition we dont need the detail of it
  [:map
   [:db-before any?]
   [:db-after any?]
   [:tx-data [:sequential any?]]
   [:tempids [:map-of any? any?]]
   [:tx-meta any?]])

(m/=> save! [:=>
             [:cat DS domain/LRA]
             domain/LRACode])

(defn save!
  [ds lra]
  (ex/try+
   (d/transact! ds [lra])
   (:lra/code lra)
   (catch Exception e
     (throw (ex-info "unkown error"
                     {::ex/type ::generic-db-error} e)))))

(m/=> all-by-status [:=>
                     [:cat DS domain/LRAStatus]
                     [:vector {:min 0} domain/LRA]])

(defn all-by-status
  [ds status]
  (ex/try+
   (->> (d/q '[:find (pull ?e [*])
               :in $ ?status
               :where [?e :lra/status ?status]]
             @ds status)
        flatten
        vec)
   (catch Exception e
     (throw (ex-info "unkown error"
                     {::ex/type ::generic-db-error} e)))))

(m/=> find-by-code [:=>
                    [:cat DS domain/LRACode]
                    [:maybe domain/LRA]])

(defn find-by-code
  [ds code]
  (ex/try+
   (d/pull @ds '[*] [:lra/code code])
   (catch Exception e
     (throw (ex-info "unkown error"
                     {::ex/type ::generic-db-error} e)))))

(m/=> set-status! [:=>
                   [:cat DS domain/LRACode domain/LRAStatus]
                   [:maybe TransactResult]])

(defn set-status!
  [ds code status]
  (ex/try+
   (d/transact! ds [{:db/id [:lra/code code] :lra/status status}])
   (catch Exception e
     (throw (ex-info "unkown error"
                     {::ex/type ::generic-db-error} e)))))
