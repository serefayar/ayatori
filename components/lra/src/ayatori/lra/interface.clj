(ns ayatori.lra.interface
  (:require
   [malli.util :as mu]
   [ayatori.lra.core :as service]
   [ayatori.lra.domain :as d]))

(def LRAStatus
  d/LRAStatus)

(def LRAData
  (-> d/LRA
      (mu/dissoc :db/id)
      (mu/dissoc :lra/time-limit)
;;      (mu/dissoc :lra/participants)
      (mu/dissoc :lra/acts)
      (mu/update-properties assoc :title "LRAData")))

(def StartLRAData
  [:map
   [:lra/client-id [:string]]
   [:lra/parent-code [:string]]
   [:lra/time-limit {:default 0} [:and
                                  [:int]
                                  [:fn (fn [v] (>= v 0))]]]
   [:lra/acts [:and [:vector {:min 2}
                     [:map
                      [:act/type d/ActType]
                      [:act/url string?]]]
               [:fn {:error/message "duplicate act type"}
                (fn [v] (every? #(= 1 %) (vals (frequencies (map :act/type v)))))]]]])

(def LRAErrorData
  (-> d/LRAError
      (mu/dissoc :type)))

(defn all-lra
  [ds status]
  (service/all-lra ds status))

(defn lra-by-code
  [ds code]
  (service/lra-by-code ds code))

(defn start-lra!
  [ds data]
  (service/start-lra! ds data))

(defn close-lra!
  [ds code]
  (service/close-lra! ds code))

(defn cancel-lra!
  [ds code]
  (service/cancel-lra! ds code))
