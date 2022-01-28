(ns ayatori.rest-api.handler
  (:require
   [ayatori.rest-api.response :as resp]
   [ayatori.lra.interface :as lra-service]
   [clojure.tools.logging :as log]
   [fmnoise.flow :as flow :refer [then then-call else]]))

(defn start-lra-handler
  [database]
  (fn [request respond _raise]
    (->> (get-in request [:parameters :body])
         (then-call #(lra-service/start-lra! (database) %))
         (then #(do (log/info (format "LRA started with code %s" %))
                    (respond (resp/created %))))
         (else #(do (log/error %)
                    (respond (resp/error %)))))))

(defn all-lra-handler
  [database]
  (fn [request respond _raise]
    (prn (-> request :lra-params))
    (->> (get-in request [:parameters :query :status])
         (then-call #(lra-service/all-lra (database) %))
         (then #(respond (resp/ok %)))
         (else #(do (log/error %)
                    (respond (resp/error %)))))))

(defn lra-handler
  [database]
  (fn [request respond _raise]
    (->> (get-in request [:path-params :code])
         (then-call #(lra-service/lra-by-code (database) %))
         (then #(dissoc % :db/id))
         (then #(respond (resp/ok %)))
         (else #(do (log/error %)
                    (respond (resp/error %)))))))

(defn close-lra-handler
  [database]
  (fn [request respond _raise]
    (->> (get-in request [:path-params :code])
         (then-call #(lra-service/close-lra! (database) %))
         (then #(respond (resp/ok %)))
         (else #(do (log/error %)
                    (respond (resp/error %)))))))

(defn cancel-lra-handler
  [database]
  (fn [request respond _raise]
    (->> (get-in request [:path-params :code])
         (then-call #(lra-service/cancel-lra! (database) %))
         (then #(respond (resp/ok %)))
         (else #(do (log/error %)
                    (respond (resp/error %)))))))
