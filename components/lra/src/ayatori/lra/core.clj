(ns ayatori.lra.core
  (:require
   [clojure.string :as string]
   [java-time :as jt]
   [ayatori.lra.db :as db]
   [malli.core :as m]
   [exoscale.ex :as ex]
   [ayatori.lra-domain.interface :as domain]
   [clojure.core.async :as async])
  (:import (clojure.core.async.impl.channels ManyToManyChannel)))

(def AsyncChannel
  ;; check an instance of for now
  [:fn (fn [v] (instance? ManyToManyChannel v))])

(m/=> closable-lra? [:=>
                     [:cat domain/LRA]
                     boolean?])
(defn closable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> cancellable-lra? [:=>
                        [:cat domain/LRA]
                        boolean?])
(defn cancellable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> joinable-lra? [:=>
                     [:cat domain/LRA]
                     boolean?])
(defn joinable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> data->lra [:=>
                 [:cat domain/StartLRAData]
                 domain/LRA])
(defn data->lra
  [data]
  (let [now        (jt/instant)
        time-limit (:lra/time-limit data)]
    (-> data
        (assoc :lra/code (db/uuid)
               :lra/start-time now
               :lra/status :active
               :lra/participants [{:participant/client-id  (:lra/client-id data)
                                   :participant/top-level? false
                                   :participant/status     :active
                                   :participant/acts       (:lra/acts data)}])
        (#(if (> time-limit 0) (assoc % :lra/finish-time (jt/plus now (jt/millis time-limit))) %))
        (dissoc :lra/acts)
        (dissoc :lra/parent-code))))

(m/=> ->toplevel-participant [:=>
                              [:cat domain/LRA]
                              domain/Participant])

(defn ->toplevel-participant
  [lra]
  {:participant/client-id  (:lra/client-id lra)
   :participant/top-level? true
   :participant/status     :active
   :participant/lra-code   (:lra/code lra)})

(m/=> data->participant [:=>
                         [:cat domain/JoinParticipantData]
                         domain/Participant])
(defn data->participant
  [data]
  (assoc data
         :participant/top-level? false
         :participant/status :active))

(m/=> all-lra [:=>
               [:cat db/DatabaseComponent domain/LRAStatus]
               [:maybe [:vector domain/LRA]]])

(defn all-lra
  [database status]
  (db/all-by-status (database) status))

(m/=> lra-by-code [:=>
                   [:cat db/DatabaseComponent domain/LRACode]
                   [:maybe domain/LRA]])
(defn lra-by-code
  [database code]
  (ex/try+
   (->
    (db/find-by-code (database) code)
    (#(or %
          (throw (ex-info (format "LRA not found with code %s" code)
                          {::ex/type ::lra-not-found :lra-code code})))))
   (catch :ayatori.lra.db/generic-db-error _
     (throw (ex-info (format "LRA not found with code %s" code)
                     {::ex/type ::lra-not-found :lra-code code})))))

(m/=> update-lra! [:=>
                   [:cat db/DatabaseComponent domain/LRA]
                   [:maybe domain/LRA]])

(defn update-lra!
  [database lra]
  (ex/try+
   (do
     (lra-by-code database (:lra/code lra))
     (db/save! (database) lra)
     (lra-by-code database (:lra/code lra)))
   (catch Exception e
     (throw (ex-info "Update LRA failed"
                     {::ex/type ::update-lra-failed} e)))))

(m/=> new-lra! [:=>
                [:cat db/DatabaseComponent domain/StartLRAData]
                [:maybe domain/LRA]])
(defn new-lra!
  [database data]
  (ex/try+
   (->> (data->lra data)
        (db/save! (database))
        (db/find-by-code (database)))
   (catch Exception e
     (throw (ex-info "Createing new LRA failed"
                     {::ex/type ::start-lra-failed} e)))))

(m/=> new-nested-lra! [:=>
                       [:cat db/DatabaseComponent domain/LRA domain/LRA]
                       [:maybe domain/LRA]])

(defn new-nested-lra!
  [database parent lra]
  (ex/try+
   (do
     (->> (->toplevel-participant lra)
          (update parent :lra/participants conj)
          (db/save! (database)))
     (db/find-by-code (database) (:lra/code lra)))
   (catch Exception e
     (throw (ex-info "Creating nested LRA failed"
                     {::ex/type ::start-nested-lra-failed} e)))))

(m/=> start-lra! [:=>
                  [:cat db/DatabaseComponent domain/StartLRAData]
                  [:maybe domain/LRA]])
(defn start-lra!
  [database data]
  (ex/try+
   (if (string/blank? (:lra/parent-code data))
     (new-lra! database data)
     ;;else
     (let [parent (lra-by-code database (:lra/parent-code data))
           lra    (new-lra! database data)]
       (new-nested-lra! database parent lra)))
   (catch Exception e
     (throw (ex-info "Start LRA failed"
                     {::ex/type ::start-lra-failed} e)))))

(m/=> join! [:=>
             [:cat db/DatabaseComponent domain/LRACode domain/JoinParticipantData]
             [:maybe domain/LRA]])
(defn join!
  [database code participant]
  (ex/try+
   (->> (lra-by-code database code)
        (#(if (joinable-lra? %)
            (->> (data->participant participant)
                 (update % :lra/participants conj)
                 (db/save! (database))
                 (db/find-by-code (database)))
            ;; else
            (throw (ex-info (format "Joinable LRA not found with code %s" code)
                            {::ex/type ::lra-not-found})))))
   (catch Exception e
     (throw (ex-info (format "Join failed with code %s" code)
                     {::ex/type ::join-lra-failed} e)))))

(m/=> close! [:=>
              [:cat db/DatabaseComponent AsyncChannel domain/LRA]
              [:maybe domain/LRACode]])
(defn close!
  [database lra-engine-input-chan {:lra/keys [code]
                                   :as       lra}]
  (if (closable-lra? lra)
    (do
      (->> (db/save! (database) (assoc lra :lra/status :processing))
           (db/find-by-code (database)) 
           (#(async/go (async/put! lra-engine-input-chan {:type :close
                                                          :lra  %}))))
      code)
    (throw (ex-info (format "Closable LRA not found with code %s" code)
                    {::ex/type ::lra-not-found}))))

(m/=> close-lra! [:=>
                  [:cat db/DatabaseComponent AsyncChannel domain/LRACode]
                  [:maybe domain/LRACode]])
(defn close-lra!
  [database lra-engine-input-chan code]
  (ex/try+
   (->> (lra-by-code database code)
        (close! database lra-engine-input-chan))
   (catch Exception e
     (throw (ex-info "Close LRA failed"
                     {::ex/type ::close-lra-failed} e)))))

(m/=> cancel! [:=>
               [:cat db/DatabaseComponent AsyncChannel domain/LRA]
               [:maybe domain/LRACode]])
(defn cancel!
  [database lra-engine-input-chan {:lra/keys [code]
                                   :as       lra}]
  (if (cancellable-lra? lra)
    (do
      (->> (db/save! (database) (assoc lra :lra/status :processing))
           (db/find-by-code (database))
           (#(async/go (async/put! lra-engine-input-chan {:type :cancel
                                                          :lra  %}))))
      code)
    (throw (ex-info (format "Cancellable LRA not found with code %s" code)
                    {::ex/type ::lra-not-found}))))

(m/=> cancel-lra! [:=>
                   [:cat db/DatabaseComponent AsyncChannel domain/LRACode]
                   [:maybe domain/LRACode]])
(defn cancel-lra!
  [database lra-engine-input-chan code]
  (ex/try+
   (->> (lra-by-code database code)
        (cancel! database lra-engine-input-chan))
   (catch Exception e
     (throw (ex-info "Cancel LRA failed"
                     {::ex/type ::cancel-lra-failed} e)))))
