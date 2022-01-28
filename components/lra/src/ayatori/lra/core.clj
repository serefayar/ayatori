(ns ayatori.lra.core
  (:require
   [clojure.string :as string]
   [fmnoise.flow :as flow :refer [call else fail-with flet then then-call]]
   [java-time :as jt]
   [ayatori.lra.db :as db]
   [ayatori.lra-engine.interface :as engine]))

(defn all-lra
  [ds status]
  (->> (call db/all-by-status ds status)
       (else #(fail-with {:msg "unknown error" :data {:type :unkown-error}}))))

(defn lra-by-code
  [ds code]
  (->> (call db/find-by-code ds code)
       (then #(or % (fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (else #(fail-with {:msg (ex-message %) :data {:type :generic-error}}))))

(defn data->lra
  [data]
  (let [now (jt/instant)
        time-limit (:lra/time-limit data)]
    (-> data
        (assoc :lra/code (.toString (java.util.UUID/randomUUID))
               :lra/start-time now
               :lra/status :active
               :lra/participants [{:participant/client-id (:lra/client-id data)
                                   :participant/top-level? false
                                   :participant/status :active
                                   :participant/acts (:lra/acts data)}])
        (#(if (> time-limit 0) (assoc % :lra/finish-time (jt/plus now (jt/millis time-limit))) %))
        (dissoc :lra/acts)
        (dissoc :lra/parent-code))))

(defn ->lra-participant
  [lra]
  {:participant/client-id (:lra/client-id lra)
   :participant/top-level? true
   :participant/lra-code (:lra/code lra)})

(defn new-lra!
  [ds data]
  (->> (data->lra data)
       (then-call #(db/save! ds %))
       (then #(db/find-by-id ds %))
       (else #(fail-with {:msg "Creating new lra failed" :data {:type :start-lra-failed} :cause %}))))

(defn new-nested-lra!
  [ds parent lra]
  (->> (->lra-participant lra)
       (update parent :lra/participants conj)
       (then #(new-lra! ds %))
       (then (fn [p] {:parent-code (:lra/code p) :lra-code (:lra/code lra)}))
       (else #(fail-with {:msg "Creating nested lra failed" :data {:type :start-nested-lra-failed} :cause %}))))

(defn start-lra!
  [ds data]
  (if (string/blank? (:lra/parent-code data))
    (->> (new-lra! ds data)
         (then #(:lra/code %)))
    (->> (flet [parent (lra-by-code ds (:lra/parent-code data))
                lra (new-lra! ds data)]
               {:parent parent :lra lra})
         (then #(new-nested-lra! ds (:parent %) (:lra %)))
         (then #(:lra-code %))
         (else #(fail-with {:msg "start lra failed" :data {:type :start-lra-failed} :cause %})))))

(defn closable-lra?
  [lra]
  (= :active (:lra/status lra)))

(defn cancellable-lra?
  [lra]
  (= :active (:lra/status lra)))

(defn close-lra!
  [ds code]
  (->> (lra-by-code ds code)
       (then-call #(if (closable-lra? %)
                     (do
                       (db/set-status! ds (:lra/code %) :closing)
                       (future (->> (engine/close! %)
                                    (db/save! ds))))
                     (fail-with {:msg (format "Closable LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (then (fn [_] code))
       (else #(fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found} :cause %}))))

(defn cancel-lra!
  [ds code]
  (->> (lra-by-code ds code)
       (then-call #(if (cancellable-lra? %)
                     (do
                       (db/set-status! ds (:lra/code %) :cancelling)
                       (future (->> (engine/cancel! %)
                                    (db/save! ds))))
                     (fail-with {:msg (format "Cancellable LRA not found with code %s" code) :data {:type :resource-not-found}})))
       (then (fn [_] code))
       (else #(fail-with {:msg (format "LRA not found with code %s" code) :data {:type :resource-not-found} :cause %}))))
