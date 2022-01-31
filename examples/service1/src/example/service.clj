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
    ["/service1"
     ["/ping"
      {:get {:handler (fn [_req respond _raise]
                        (respond (resp/response "pong")))}}]
     ["/order"
      {:lra {:id :order
             :type :required-new}
       :put {:parameters {:query {:num int?}}
             :handler (fn [request respond _]
                        (let [lra (-> request :lra-params)
                              num (-> request :parameters :query :num)]

                          (prn (format "service1 param %s, lra context created with code %s" num (:code lra)))

                          (try
                            (-> (client/put (format "http://localhost:5000/service2/order?num=%s" (str (+ num 1))) {:headers (-> request :lra-headers)})
                                :body
                                str
                                (resp/response)
                                respond)
                            (catch Throwable _
                              (respond (resp/bad-request "bad request"))))))}}]
     ["/compansate"
      {:lra {:id :order
             :type :compansate}
       :put {:handler (fn [request respond _]
                        (prn (format "service1 compansating lra %s" (-> request :lra-params :code)))
                        (respond (resp/response "ok")))}}]
     ["/complete"
      {:lra {:id :order
             :type :complete}
       :put {:handler (fn [request respond _]
                        (prn (format "service1 completing lra %s" (-> request :lra-params :code)))
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
  (jetty/run-jetty #'app {:port 4000, :join? false, :async? true})
  (println "service1 running in port 4000"))
