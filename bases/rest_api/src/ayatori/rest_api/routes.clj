(ns ayatori.rest-api.routes
  (:require [malli.util :as mu]
            [muuntaja.core :as m]
            [reitit.coercion.malli]
            [reitit.ring :as ring]
            [reitit.ring.coercion :as coercion]
            [reitit.ring.malli]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [reitit.swagger-ui :as swagger-ui]
            [ayatori.rest-api.handler :as handler]
            [ayatori.lra-domain.interface :as lra]))

(defn app-handler
  [context-path {:keys [database]}]
  (ring/ring-handler
   (ring/router
    [["/swagger.json"
      {:get {:no-doc  true
             :swagger {:info     {:title       "Ayatori LRA"
                                  :description "Ayatori LRA API"}}
             :handler (swagger/create-swagger-handler)}}]
     [context-path {:swagger {:securityDefinitions {:BasicAuth {:type :basic}}
                              :security {:BasicAuth []}}}
      [[""
        {:get {:summary "list of LRAs"

               :parameters {:query [:map [:status lra/LRAStatus]]}
               :responses {200 {:body [:vector lra/LRAData]}
                           400 {:body string?}}
               :handler (handler/all-lra-handler database)}}]
       ["/start"
        {:post  {:summary   "start"
                 :parameters {:body lra/StartLRAData}
                 :responses {201 {:body string?}
                             400 {:body string?}
                             404 {:body string?}
                             417 {:body string?}
                             500 {:body string?}}
                 :handler   (handler/start-lra-handler database)}}]
       ["/:code"
        {:get {:summary "get lra"
               :parameters {:path [:map [:code uuid?]]}
               :responses {200 {:body lra/LRAData}
                           400 {:body string?}
                           404 {:body string?}
                           417 {:body string?}}
               :handler (handler/lra-handler database)}
         :put {:summary "join lra"
               :parameters {:path [:map [:code uuid?]]
                            :body lra/JoinParticipantData}
               :responses {200 {:body string?}}
               :handler (handler/join-to-lra-handler database)}}]
       ["/:code/close"
        {:put {:summary "close lra"
               :parameters {:path [:map [:code uuid?]]}
               :responses {200 {:body string?}}
               :handler (handler/close-lra-handler database)}}]
       ["/:code/cancel"
        {:put {:summary "cancel lra"
               :parameters {:path [:map [:code uuid?]]}
               :responses {200 {:body string?}}
               :handler (handler/cancel-lra-handler database)}}]]]]

    {:conflicts nil
     :data             {:context-path context-path
                        :coercion (reitit.coercion.malli/create
                                   {:error-keys #{#_:type #_:coercion #_:in #_:schema #_:value :errors #_:humanized #_:transformed}
                                    :compile mu/closed-schema
                                    :strip-extra-keys true
                                    :default-values true
                                    :options nil})
                        :muuntaja   m/instance
                        :middleware [swagger/swagger-feature
                                     parameters/parameters-middleware
                                     muuntaja/format-negotiate-middleware
                                     muuntaja/format-response-middleware
                                     muuntaja/format-request-middleware
                                     coercion/coerce-response-middleware
                                     coercion/coerce-request-middleware]}})
   (ring/routes
    (swagger-ui/create-swagger-ui-handler
     {:path   "/api-docs"
      :url "/swagger.json"
      :config {:validatorUrl     nil
               :operationsSorter "alpha"}})
    (ring/redirect-trailing-slash-handler {:method :strip})
    (ring/create-default-handler))))
