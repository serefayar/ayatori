(ns ayatori.lra.domain
  (:import [fmnoise.flow Fail]))

(def LRACode
  [:string])

(def LRAStatus
  [:enum {:decode/string '#(-> % name clojure.string/lower-case keyword)
          :decode/json '#(-> % name clojure.string/lower-case keyword)}
   :active :cancelled :cancelling :closed :mark-to-close :closing :failed-to-cancel :failed-to-close])

(def ParticipantStatus
  [:enum {:decode/string '#(-> % name clojure.string/lower-case keyword)
          :decode/json '#(-> % name clojure.string/lower-case keyword)}
   :active :compansating :compansated :failed-to-compansate :completing :completed :failed-to-complete])

(def ActType
  [:enum {:decode/string '#(-> % name clojure.string/lower-case keyword)
          :decode/json '#(-> % name clojure.string/lower-case keyword)}
   :compansate :complete])

(def Act
  [:map
   [:act/type ActType]
   [:act/url [:string]]])

(def Participant
  [:schema {:registry {::participant [:map
                                      [:participant/client-id [:string]]
                                      [:participant/top-level? [:boolean]]
                                      [:participant/status ParticipantStatus]
                                      [:participant/participants {:optional true} [:vector [:or
                                                                                            [:ref ::participant]
                                                                                            [:map
                                                                                             [:participant/client-id [:string]]
                                                                                             [:participant/top-level? {:default true}
                                                                                              [:and
                                                                                               [:boolean]
                                                                                               [:fn #(true? %)]]]
                                                                                             [:participant/lra-code LRACode]]]]]

                                      [:participant/acts [:vector {:min 2} Act]]]}}
   ::participant])

(def LRA
  [:map
   [:lra/code LRACode]
   [:lra/start-time inst?]
   [:lra/time-limit {:default 0} [:int]]
   [:lra/finish-time {:optional true} inst?]
   [:lra/participants {:optional true} [:vector Participant]]

   [:lra/status LRAStatus]])

(def LRAErrorType
  [:enum :generic-error :duplicate-id :start-lra-failed :start-nested-lra-failed :resource-not-found :validation])
