(ns example.service
  (:require [reitit.ring :as ring]
            [ring.util.response :as resp]
            [ring.adapter.jetty :as jetty]
            [clj-http.client :as client]
            [muuntaja.core :as m]
            [reitit.coercion.spec]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [ayatori.reitit.middleware :as ayatori]))

(def app
  (ring/ring-handler
   (ring/router
    ["/service3"
     ["/order"
      {:lra {:id :order-s3
             :type :requires-new}
       :put {:parameters {:query {:num int?}}
             :handler (fn [request respond _]
                        (let [lra (-> request :lra-params)
                              num (-> request :parameters :query :num)]

                          (prn (format "service3 param %s, create new context with parent context %s, lra context %s" 
                                       num (:parent lra) (:code lra)))
                          (prn (-> request :lra-headers))
                          (try
                            (-> (client/put (format "http://localhost:7000/service4/order?num=%s" (+ num 1)) {:headers (-> request :lra-headers)})
                                :body
                                (resp/response)
                                respond)
                            (catch Throwable e
                              (prn e)
                              (respond (resp/bad-request "bad request"))))))}}]
     ["/compensate"
      {:lra {:id :order-s3
             :type :compensate}
       :put {:handler (fn [request respond _]
                        (prn (format "service3 compansating lra %s" (-> request :lra-params :code)))
                        (respond (resp/response "ok")))}}]
     ["/complete"
      {:lra {:id :order-s3
             :type :complete}
       :put {:handler (fn [request respond _]
                        (prn (format "service3 completing lra %s" (-> request :lra-params :code)))
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
  (jetty/run-jetty #'app {:port 6000, :join? false, :async? true})
  (println "service3 running in port 6000"))
