(ns ayatori.lra.interface
  (:require
   [exoscale.ex :as ex]
   [ayatori.lra.core :as service]
   [ayatori.lra.db :as db]))

(ex/derive ::lra-not-found ::ex/not-found)
(ex/derive ::start-lra-failed ::ex/fault)
(ex/derive ::start-nested-lra-failed ::ex/fault)
(ex/derive ::update-lra-failed ::ex/fault)
(ex/derive ::join-lra-failed ::ex/fault)


(def DatabaseComponent
  ;; check an instance of for now
  db/DatabaseComponent)

(defn all-lra
  [database status]
  (service/all-lra database status))

(defn lra-by-code
  [database code]
  (service/lra-by-code database code))

(defn update-lra!
  [database lra]
  (service/update-lra! database lra))

(defn start-lra!
  [database data]
  (service/start-lra! database data))

(defn join!
  [database code participant]
  (service/join! database code participant))

(defn close-lra!
  [database lra-engine-input-chan code]
  (service/close-lra! database lra-engine-input-chan code))

(defn cancel-lra!
  [database lra-engine-input-chan code]
  (service/cancel-lra! database lra-engine-input-chan code))
