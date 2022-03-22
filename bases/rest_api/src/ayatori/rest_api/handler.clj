(ns ayatori.rest-api.handler
  (:require
   [ayatori.rest-api.response :as resp]
   [ayatori.lra.interface :as lra-service]
   [clojure.tools.logging :as log]
   [exoscale.ex :as ex]))

(defn start-lra-handler
  [database]
  (fn [request respond _raise]
    (ex/try+
     (->> (-> request :parameters :body)
          (lra-service/start-lra! database)
          (#(do (log/info (format "LRA started with code %s" %))
                (respond (resp/created %)))))
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))

(defn join-to-lra-handler
  [database]
  (fn [request respond _raise]
    (ex/try+ 
     (->> (-> request :parameters :body)
          (lra-service/join! database (-> request :path-params :code))
          (#(do
              (log/info (format "Participant joined to lra %s" %))
              (respond (resp/ok %)))))
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))

(defn all-lra-handler
  [database]
  (fn [request respond _raise]
    (ex/try+
     (->> (-> request :parameters :query :status)
          (lra-service/all-lra database)
          (resp/ok)
          respond)
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))

(defn lra-handler
  [database]
  (fn [request respond _raise]
    (ex/try+ 
     (->> (-> request :path-params :code)
          (lra-service/lra-by-code database)
          (#(dissoc % :db/id))
          (resp/ok)
          respond)
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))

(defn close-lra-handler
  [database lra-engine-input-chan]
  (fn [request respond _raise]
    (ex/try+ 
     (->> (-> request :path-params :code)
          (lra-service/close-lra! database lra-engine-input-chan)
          (resp/ok)
          respond)
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))

(defn cancel-lra-handler
  [database lra-engine-input-chan]
  (fn [request respond _raise]
    (ex/try+ 
     (->> (-> request :path-params :code)
          (lra-service/cancel-lra! database lra-engine-input-chan)
          (resp/ok)
          respond)
     (catch Exception e
       (do (log/error (ex-message e))
           (respond (resp/error e)))))))
