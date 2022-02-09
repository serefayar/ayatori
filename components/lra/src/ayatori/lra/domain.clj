(ns ayatori.lra.domain
  (:require
   [java-time :as jt]
   [malli.util :as mu])
  (:import
   [fmnoise.flow Fail]))

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

(def TopLevelParticipant
  [:map
   [:participant/client-id [:string]]
   [:participant/status ParticipantStatus]
   [:participant/top-level? {:default true}
    [:and
     [:boolean]
     [:fn #(true? %)]]]
   [:participant/lra-code LRACode]])

(def Participant
  [:schema {:registry {::participant [:map
                                      [:participant/client-id [:string]]
                                      [:participant/top-level? [:boolean]]
                                      [:participant/status ParticipantStatus]
                                      [:participant/participants {:optional true} [:vector [:or
                                                                                            [:ref ::participant]
                                                                                            TopLevelParticipant]]]

                                      [:participant/acts [:vector {:min 2} Act]]]}}
   ::participant])

(def LRA
  [:and
   [:map
    [:lra/code LRACode]
    [:lra/start-time inst?]
    [:lra/time-limit {:default 0} [:int]]
    [:lra/finish-time {:optional true} inst?]
    [:lra/participants {:optional true} [:vector Participant]]
    [:lra/status LRAStatus]]
   [:fn (fn [{:lra/keys [start-time finish-time]}] (or (nil? finish-time)
                                                       (jt/after? finish-time start-time)))]])

(def LRAErrorType
  [:fn (fn [v]
         (and (instance? Fail v)
              (-> v
                  ex-data
                  :type
                  (#(some #{%}
                          [:generic-error :duplicate-id :start-lra-failed :start-nested-lra-failed :resource-not-found :validation])))))])

(def LRAData
  (-> LRA
      second ;; TODO: is there a better way?
      (mu/dissoc :db/id)
      (mu/dissoc :lra/time-limit)
      (mu/dissoc :lra/participants)
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
                      [:act/type ActType]
                      [:act/url string?]]]
               [:fn {:error/message "duplicate act type"}
                (fn [v] (every? #(= 1 %) (vals (frequencies (map :act/type v)))))]]]])

(def JoinParticipantData
  (-> Participant
      (mu/dissoc :participant/top-level?)
      (mu/dissoc :participant/status)
      (mu/dissoc :participant/participants)
      (mu/update-properties assoc :title "JoinParticipantData")))

(comment
  {:participant/client-id "aaa"
   :participant/top-level? false
   :participant/status :active
   :participant/acts [{:act/type :complete :act/url "http://localhost:6000/bbb/complete"}
                      {:act/type :compansate :act/url "http://localhost:6000/bbb/compansate"}]})
