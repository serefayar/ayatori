(ns ayatori.lra-engine.core
  (:require
   [tilakone.core :as tk]
   [clj-http.client :as client]
   [malli.core :as m]
   [clojure.tools.logging :as log]
   [clojure.core.async :as async]
   [com.stuartsierra.component :as component]
   [ayatori.lra-domain.interface :as domain]
   [ayatori.lra.interface :as lra]
   [exoscale.ex :as ex]))

(def EngineState
  [:map
   [:tilakone.core/states [:vector [:map
                                    [:tilakone.core/name keyword?]
                                    [:tilakone.core/transitions {:optional true}
                                     [:vector [:map
                                               [:tilakone.core/on keyword?]
                                               [:tilakone.core/to keyword?]
                                               [:tilakone.core/actions [:vector keyword?]]]]]]]]
   [:tilakone.core/action! ifn?]
   [:tilakone.core/state keyword?]
   [:lra domain/LRA]
   [:database lra/DatabaseComponent]])

(def context-headers
  {:context  "Long-Running-Action"
   :ended    "Long-Running-Action-Ended"                    ;; not implemented
   :parent   "Long-Running-Action-Parent"                   ;; not implemented
   :recovery "Long-Running-Action-Recovery"                 ;; not implemented
   })

(m/=> http-request! [:=>
                     [:cat keyword? string? [:* any?]]
                     any?])

(defn http-request!
  [method url & [opts]]
  (client/request
   (merge {:method method
           :url url
           :socket-timeout 1000
           :connection-timeout 1000
           :content-type :json
           :accept :json} opts)))

(m/=> find-by-act-type [:=>
                        [:cat domain/Participant domain/ActType]
                        [:or domain/Act nil?]])

(defn find-by-act-type
  [participant act-type]
  (first (filter #(= act-type (:act/type %)) (:participant/acts participant))))


(declare process-message!)
;;
;; cancel LRA
;;

(declare cancel!)

(m/=> compensate-participant! [:=>
                               [:cat domain/LRACode domain/Participant]
                               domain/Participant])

(defn compensate-participant!
  [lra-code participant]
  (if-let [act (find-by-act-type participant :compensate)]
    (ex/try+
     (http-request! :put (:act/url act) {:headers {(:context context-headers) lra-code}})
     (log/infof "Participant compensated %s" (:act/url act))
     (assoc participant :participant/status :compensated)
     (catch Exception _
       (log/errorf "%s request to %s failed" (:act/type act) (:act/url act))
       (assoc participant :participant/status :failed-to-compensate)))
    ;; else
    (do
      (log/infof "No Participant found to compensate %s" lra-code)
      participant)))

(m/=> compensate-nested-participant! [:=>
                                      [:cat lra/DatabaseComponent domain/Participant]
                                      domain/Participant])
(defn compensate-nested-participant!
  [database {:participant/keys [lra-code] :as participant}]
  (ex/try+
   (some->> (lra/lra-by-code database lra-code)
            (#(assoc % :lra/status :processing))
            (lra/update-lra! database)
            (process-message! database :cancel)
            (#(if (= :cancelled (:lra/status %))
                :compensated
                :failed-to-compensate))
            (assoc participant :participant/status))
   (catch Exception e
     (do
       (log/info (format "Found to compensate %s nested participant" lra-code) e)
       participant))))

(m/=> compensate! [:=>
                   [:cat lra/DatabaseComponent domain/LRACode [:vector domain/Participant]]
                   [:vector domain/Participant]])

(defn compensate!
  [database lra-code participants]
  (->> (reverse participants)
       (map #(if (:participant/top-level? %)
               (compensate-nested-participant! database %)
               (compensate-participant! lra-code %)))
       reverse
       vec))

(m/=> cancel-lra! [:=>
                   [:cat EngineState]
                   EngineState])

(defn cancel-lra!
  [{:keys [lra database] :as fsm}]
  (->> (compensate! database (:lra/code lra) (:lra/participants lra))
       (assoc-in fsm [:lra :lra/participants])))

(m/=> cancel-success [:=>
                      [:cat EngineState]
                      EngineState])

(defn cancel-success
  [fsm]
  (log/infof "LRA %s cancelled successfully" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :cancelled))

(m/=> cancel-failed [:=>
                     [:cat EngineState]
                     EngineState])

(defn cancel-failed
  [fsm]
  (log/errorf "Cancelling LRA %s failed" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :failed-to-cancel))

;;
;; close LRA
;;

(m/=> complete-participant! [:=>
                             [:cat domain/LRACode domain/Participant]
                             domain/Participant])

(defn complete-participant!
  [lra-code participant]
  (if-let [act (find-by-act-type participant :complete)]
    (ex/try+
     (http-request! :put (:act/url act) {:headers {(:context context-headers) lra-code}})
     (log/infof "Participant closed %s" (:act/url act))
     (assoc participant :participant/status :completed)
     (catch Exception _
       (log/errorf "%s request to %s failed" (:act/type act) (:act/url act))
       (assoc participant :participant/status :failed-to-complete)))
    ;; else
    (do
      (log/infof "No Participant found to complete %s" lra-code)
      participant)))

(m/=> complete-nested-participant! [:=>
                                    [:cat lra/DatabaseComponent domain/Participant]
                                    domain/Participant])

(defn complete-nested-participant!
  [database {:participant/keys [lra-code] :as participant}]
  (ex/try+
   (let [lra (lra/lra-by-code database lra-code)]
     (if (= :active (:lra/status lra)) 
       (some->> lra
                (#(assoc % :lra/status :processing))
                (lra/update-lra! database)
                (process-message! database :close)
                (#(if (= :closed (:lra/status %))
                    :completed
                    :failed-to-complete))
                (assoc participant :participant/status))
       ;; else
       (log/infof "nested participant with lra code %s already closed" lra-code)))
   
   (catch Exception e
     (do
       (log/info (format "Found to complete %s nested participant" lra-code) e)
       participant))))

(m/=> complete! [:=>
                 [:cat lra/DatabaseComponent domain/LRACode [:vector domain/Participant]]
                 [:vector domain/Participant]])

(defn complete!
  [database lra-code participants] 
  (->> participants
       (map #(if (:participant/top-level? %)
               (complete-nested-participant! database %)
               (complete-participant! lra-code %)))
       vec))

(m/=> close-lra! [:=>
                  [:cat EngineState]
                  EngineState])

(defn close-lra!
  [{:keys [database lra] :as fsm}] 
  (->> (complete! database (:lra/code lra) (:lra/participants lra))
       (assoc-in fsm [:lra :lra/participants])))

(m/=> close-success [:=>
                     [:cat EngineState]
                     EngineState])

(defn close-success
  [fsm]
  (log/infof "LRA %s closed successfully" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :closed))

(m/=> close-failed [:=>
                    [:cat EngineState]
                    EngineState])

(defn close-failed
  [fsm]
  (log/errorf "Closing LRA %s failed" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :failed-to-close))

;;
;; state
;;
(def lra-states
  [{::tk/name        :active}
   {::tk/name        :processing
    ::tk/transitions [{::tk/on :close ::tk/to :closing ::tk/actions [:close-lra]}
                      {::tk/on :cancel ::tk/to :cancelling ::tk/actions [:cancel-lra]}]}
   {::tk/name        :cancelling
    ::tk/transitions [{::tk/on :success ::tk/to :cancelled ::tk/actions [:cancel-success]}
                      {::tk/on :failed ::tk/to :failed-to-cancel ::tk/actions [:cancel-failed]}]}
   {::tk/name :cancelled}
   {::tk/name :failed-to-cancel}
   {::tk/name        :closing
    ::tk/transitions [{::tk/on :success ::tk/to :closed ::tk/actions [:close-success]}
                      {::tk/on :failed ::tk/to :failed-to-close ::tk/actions [:close-failed]}]}
   {::tk/name :closed}
   {::tk/name :failed-to-close}])

(m/=> make-state [:=>
                  [:cat lra/DatabaseComponent domain/LRA]
                  EngineState])

(defn make-state
  [database lra]
  {::tk/states  lra-states
   ::tk/action! (fn [{::tk/keys [action]
                      :as      fsm}]
                  (case action
                    :close-lra (close-lra! fsm)
                    :close-success (close-success fsm)
                    :close-failed (close-failed fsm)
                    :cancel-lra (cancel-lra! fsm)
                    :cancel-success (cancel-success fsm)
                    :cancel-failed (cancel-failed fsm)))
   ::tk/state   (:lra/status lra)
   :lra        lra
   :database   database})

(m/=> participant-by-status [:=>
                             [:cat domain/ParticipantStatus
                              [:vector domain/Participant]]
                             [:sequential domain/Participant]])

(defn participant-by-status
  [status participants]
  (filter #(= status (:participant/status %)) participants))

(m/=> close! [:=>
              [:cat lra/DatabaseComponent domain/LRA]
              domain/LRA])

(defn close!
  [database lra]
  (-> (make-state database lra)
      (tk/apply-signal :close)
      ((fn [fsm]
         (if (seq (participant-by-status :failed-to-complete (-> fsm :lra :lra/participants)))
           (tk/apply-signal fsm :failed)
           (tk/apply-signal fsm :success))))
      :lra))

(m/=> cancel! [:=>
               [:cat lra/DatabaseComponent domain/LRA]
               domain/LRA])

(defn cancel!
  [database lra]
  (-> (make-state database lra)
      (tk/apply-signal :cancel)
      ((fn [fsm]
         (if (seq (participant-by-status :failed-to-compensate (-> fsm :lra :lra/participants)))
           (tk/apply-signal fsm :failed)
           (tk/apply-signal fsm :success))))
      :lra))

(defn process-message!
  [database type lra]
  (when-let [lra (condp = type
                   :cancel (cancel! database lra)
                   :close (close! database lra)
                   nil)]
    (ex/try+
     (lra/update-lra! database lra)
     (catch Exception e
       (log/error (format "failed to process message type %s for lra %s" type (:lra/code lra)) e)))))

(defrecord LRAEngine [input-chan
                      database]
  component/Lifecycle
  (start [this]
    (let [stop-chan (async/chan 1)]
      (async/go-loop []
        (async/alt!
          input-chan
          ([{:keys [type lra]}] 
           (process-message! database type lra)
           (recur))
          stop-chan
          ([_]
           :no-op)))
      (assoc this :stop-chan stop-chan)))
  (stop [this]
    (async/put! (:stop-chan this) :stop)
    (assoc this :input-chan nil)))

(defn make-lraengine
  []
  (component/using (map->LRAEngine {})
                   {:input-chan :lra-engine-input-chan
                    :database   :database}))
