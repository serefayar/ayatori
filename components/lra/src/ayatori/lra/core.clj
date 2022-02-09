(ns ayatori.lra.core
  (:require
   [clojure.string :as string]
   [fmnoise.flow :as flow :refer [call else fail-with flet then then-call]]
   [java-time :as jt]
   [ayatori.lra.db :as db]
   [ayatori.lra-engine.interface :as engine]
   [malli.core :as m]
   [ayatori.lra.domain :as domain]))

(m/=> closable-lra? [:=> [:cat domain/LRA] boolean?])
(defn closable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> cancellable-lra? [:=> [:cat domain/LRA] boolean?])
(defn cancellable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> joinable-lra? [:=> [:cat domain/LRA] boolean?])
(defn joinable-lra?
  [lra]
  (= :active (:lra/status lra)))

(m/=> data->lra [:=> [:cat domain/StartLRAData] domain/LRA])
(defn data->lra
  [data]
  (let [now (jt/instant)
        time-limit (:lra/time-limit data)]
    (-> data
        (assoc :lra/code (str (java.util.UUID/randomUUID))
               :lra/start-time now
               :lra/status :active
               :lra/participants [{:participant/client-id (:lra/client-id data)
                                   :participant/top-level? false
                                   :participant/status :active
                                   :participant/acts (:lra/acts data)}])
        (#(if (> time-limit 0) (assoc % :lra/finish-time (jt/plus now (jt/millis time-limit))) %))
        (dissoc :lra/acts)
        (dissoc :lra/parent-code))))

(m/=> ->toplevel-participant [:=> [:cat domain/StartLRAData] domain/TopLevelParticipant])
(defn ->toplevel-participant
  [lra]
  {:participant/client-id (:lra/client-id lra)
   :participant/top-level? true
   :participant/status :active
   :participant/lra-code (:lra/code lra)})

(m/=> data->participant [:=> [:cat domain/JoinParticipantData] domain/Participant])
(defn data->participant
  [data]
  (assoc data
         :participant/top-level? false
         :participant/status :active))

(m/=> all-lra [:=> [:cat db/DS domain/LRAStatus] [:or domain/LRA domain/LRAErrorType]])
(defn all-lra
  [ds status]
  (->> (call db/all-by-status ds status)
       (else #(fail-with {:msg "unknown error" :data {:type :unkown-error} :cause %}))))

(m/=> lra-by-code [:=> [:cat db/DS domain/LRACode] [:or domain/LRA domain/LRAErrorType]])
(defn lra-by-code
  [ds code]
  (->> (call db/find-by-code ds code)
       (then #(or % (fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (else #(fail-with {:msg (ex-message %) :data {:type (or (-> % ex-data :type) :generic-error)}}))))

(m/=> new-lra! [:=> [:cat db/DS domain/StartLRAData] [:or domain/LRA domain/LRAErrorType]])
(defn new-lra!
  [ds data]
  (->> (data->lra data)
       (then-call #(db/save! ds %))
       (else #(fail-with {:msg "Creating new lra failed" :data {:type :start-lra-failed} :cause %}))))

(m/=> new-nested-lra! [:=> [:cat db/DS domain/LRA domain/LRA] [:or [:map [:parent-code domain/LRACode] [:lra-code domain/LRACode]] domain/LRAErrorType]])
(defn new-nested-lra!
  [ds parent lra]
  (->> (->toplevel-participant lra)
       (update parent :lra/participants conj)
       (then-call #(new-lra! ds %))
       (then (fn [p] {:parent-code (:lra/code p) :lra-code (:lra/code lra)}))
       (else #(fail-with {:msg "Creating nested lra failed" :data {:type :start-nested-lra-failed} :cause %}))))

(m/=> start-lra! [:=> [:cat db/DS domain/StartLRAData] [:or domain/LRA domain/LRAErrorType]])
(defn start-lra!
  [ds data]
  (if (string/blank? (:lra/parent-code data))
    (->> (call new-lra! ds data)
         (then #(:lra/code %)))
    (->> (flet [parent (lra-by-code ds (:lra/parent-code data))
                lra (new-lra! ds data)]
               {:parent parent :lra lra})
         (then #(new-nested-lra! ds (:parent %) (:lra %)))
         (then #(:lra-code %))
         (else #(fail-with {:msg "start lra failed" :data {:type :start-lra-failed} :cause %})))))

(m/=> join! [:=> [:cat db/DS domain/LRACode domain/JoinParticipantData] [:or domain/LRACode domain/LRAErrorType]])
(defn join!
  [ds code participant]
  (->> (lra-by-code ds code)
       (then-call #(if (joinable-lra? %)
                     (->> (data->participant participant)
                          (update % :lra/participants conj)
                          (db/save! ds))
                     (fail-with {:msg (format "Joinable LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (then #(:lra/code %))
       (else #(fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found} :cause %}))))

(m/=> close! [:=> [:cat db/DS domain/LRA] [:fn future?]])
(defn close!
  [ds lra]
  (future (->> (engine/close! lra)
               (db/save! ds))))

(m/=> close-lra! [:=> [:cat db/DS domain/LRACode] [:or domain/LRACode domain/LRAErrorType]])
(defn close-lra!
  [ds code]
  (->> (lra-by-code ds code)
       (then-call #(if (closable-lra? %)
                     (do
                       (db/set-status! ds (:lra/code %) :closing)
                       (close! ds %))
                     (fail-with {:msg (format "Closable LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (then (fn [_] code))
       (else #(fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found} :cause %}))))

(m/=> cancel! [:=> [:cat db/DS domain/LRA] [:fn future?]])
(defn cancel!
  [ds lra]
  (future (->> (engine/cancel! lra)
               (db/save! ds))))

(m/=> cancel-lra! [:=> [:cat db/DS domain/LRACode] [:or domain/LRACode domain/LRAErrorType]])
(defn cancel-lra!
  [ds code]
  (->> (lra-by-code ds code)
       (then-call #(if (cancellable-lra? %)
                     (do
                       (db/set-status! ds (:lra/code %) :cancelling)
                       (cancel! ds %))
                     (fail-with {:msg (format "Cancellable LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (then (fn [_] code))
       (else #(fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found} :cause %}))))
