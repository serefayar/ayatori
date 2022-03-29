(ns example.service
  (:require [reitit.ring :as ring]
            [ring.util.response :as resp]
            [ring.adapter.jetty :as jetty]
            [muuntaja.core :as m]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ayatori.reitit.middleware :as ayatori]))

(def app
  (ring/ring-handler
   (ring/router
    ["/service4"
     ["/order"
      {:lra {:id :order-s4
             :type :mandatory}
       :put {:parameters {:query {:num int?}}
             :handler (fn [request respond _]
                        (let [lra (-> request :lra-params)
                              num (-> request :parameters :query :num)]

                          (prn (format "service4 param %s, joined to lra context %s" num (:code lra)))

                          (respond (resp/response (str (+ num 1))))))}}]
     ["/compensate"
      {:lra {:id :order-s4
             :type :compensate}
       :put {:handler (fn [request respond _]
                        (prn (format "service4 compansating lra %s" (-> request :lra-params :code)))
                        (respond (resp/response "ok")))}}]
     ["/complete"
      {:lra {:id :order-s4
             :type :complete}
       :put {:handler (fn [request respond _]
                        (prn (format "service4 completing lra %s" (-> request :lra-params :code)))
                        (respond (resp/response "ok")))}}]]
    {:data {:coercion   reitit.coercion.spec/coercion
            :muuntaja   m/instance
            :middleware [parameters/parameters-middleware
                         rrc/coerce-request-middleware
                         muuntaja/format-response-middleware
                         rrc/coerce-response-middleware
                         [ayatori/create-lra-middleware {:coordinator-url "http://localhost:3000/lra-coordinator"}]]}})

   (ring/create-default-handler)))

(defn -main []
  (jetty/run-jetty #'app {:port 7000, :join? false, :async? true})
  (println "service4 running in port 7000"))
