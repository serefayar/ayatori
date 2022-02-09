(ns ayatori.lra.interface
  (:require
   [ayatori.lra.core :as service]))

(defn all-lra
  [ds status]
  (service/all-lra ds status))

(defn lra-by-code
  [ds code]
  (service/lra-by-code ds code))

(defn start-lra!
  [ds data]
  (service/start-lra! ds data))

(defn join!
  [ds code participant]
  (service/join! ds code participant))

(defn close-lra!
  [database code]
  (service/close-lra! (database) code))

(defn cancel-lra!
  [ds code]
  (service/cancel-lra! ds code))
