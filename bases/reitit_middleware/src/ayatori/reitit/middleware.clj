(ns ayatori.reitit.middleware
  (:require [reitit.core :as r]
            [ayatori.ring.interface :as rm]))

(defn prepare-options
  [request options]
  (assoc options
         :current-lra (-> request :reitit.core/match :data :lra)
         :router (r/compiled-routes (-> request :reitit.core/router))))

(defn wrap-lra
  [handler options]
  (fn
    ([request]
     (->> (prepare-options request options)
          (rm/lra-handler-sync handler request)))

    ([request respond raise]
     (->> (prepare-options request options)
          (rm/lra-handler-async handler request respond raise)))))

(def create-lra-middleware
  {:name ::lra
   :wrap wrap-lra})
