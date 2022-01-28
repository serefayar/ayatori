(ns ayatori.lra-engine.core
  (:require
   [tilakone.core :as tk]
   [clj-http.client :as client]
   [clojure.tools.logging :as log]))

(def context-headers
  {:context "Long-Running-Action"
   :ended "Long-Running-Action-Ended" ;; not implemented
   :parent "Long-Running-Action-Parent" ;; not implemented
   :recovery "Long-Running-Action-Recovery" ;; not implemented
   })

(defn http-request!
  [method url & [opts]]
  (client/request
   (merge {:method method :url url :socket-timeout 1000 :connection-timeout 1000 :content-type :json :accept :json} opts)))

(defn find-by-act-type
  [participant act-type]
  (first (filter #(= act-type (:act/type %)) (:participant/acts participant))))

;;
;; cancel LRA
;;

(defn compansate-participant!
  [lra-code participant]
  (let [act (find-by-act-type participant :compansate)]
    (try
      (http-request! :put (:act/url act) {:headers {(:context context-headers) lra-code}})
      (log/infof "Participant compansated %s" (:act/url act))
      (assoc participant :participant/status :compansated)
      (catch Exception _
        (log/errorf "%s request to %s failed" (:act/type act) (:act/url act))
        (assoc participant :participant/status :failed-to-compansate)))))

(defn compansate!
  [lra-code participants]
  (->> (reverse participants)
       (map #(compansate-participant! lra-code %))
       reverse))

(defn cancel-lra!
  [{:keys [lra] :as fsm}]
  (->> (compansate! (:lra/code lra) (:lra/participants lra))
       (assoc-in fsm [:lra :lra/participants])))

(defn cancel-success
  [fsm]
  (log/infof "LRA %s cancelled successfully" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :cancelled))

(defn cancel-failed
  [fsm]
  (log/errorf "Cancelling LRA %s failed" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :failed-to-cancel))

;;
;; close LRA
;;

(defn complete-participant!
  [lra-code participant]
  (let [act (find-by-act-type participant :complete)]
    (try
      (http-request! :put (:act/url act) {:headers {(:context context-headers) lra-code}})
      (log/infof "Participant closed %s" (:act/url act))
      (assoc participant :participant/status :completed)
      (catch Exception _
        (log/errorf "%s request to %s failed" (:act/type act) (:act/url act))
        (assoc participant :participant/status :failed-to-complete)))))

(defn complete!
  [lra-code participants]
  (->> participants
       (map #(complete-participant! lra-code %))
       reverse))

(defn close-lra!
  [{:keys [lra] :as fsm}]
  (->> (complete! (:lra/code lra) (:lra/participants lra))
       (assoc-in fsm [:lra :lra/participants])))

(defn close-success
  [fsm]
  (log/infof "LRA %s closed successfully" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :closed))

(defn close-failed
  [fsm]
  (log/errorf "Closing LRA %s failed" (-> fsm :lra :lra/code))
  (assoc-in fsm [:lra :lra/status] :failed-to-close))

;;
;; state
;;
(def lra-states
  [{::tk/name :active
    ::tk/transitions [{::tk/on :close ::tk/to :closing ::tk/actions [:close-lra]}
                      {::tk/on :cancel ::tk/to :cancelling ::tk/actions [:cancel-lra]}]}
   {::tk/name :cancelling
    ::tk/transitions [{::tk/on :success ::tk/to :cancelled ::tk/actions [:cancel-success]}
                      {::tk/on :failed ::tk/to :failed-to-cancel ::tk/actions [:cancel-failed]}]}
   {::tk/name :cancelled}
   {::tk/name :failed-to-cancel}
   {::tk/name :closing
    ::tk/transitions [{::tk/on :success ::tk/to :closed ::tk/actions [:close-success]}
                      {::tk/on :failed ::tk/to :failed-to-close ::tk/actions [:close-failed]}]}
   {::tk/name :closed}
   {::tk/name :failed-to-close}])

(defn make-state
  [lra]
  {::tk/states lra-states
   ::tk/action! (fn [{::tk/keys [action] :as fsm}]
                  (case action
                    :close-lra (close-lra! fsm)
                    :close-success (close-success fsm)
                    :close-failed (close-failed fsm)
                    :cancel-lra (cancel-lra! fsm)
                    :cancel-success (cancel-success fsm)
                    :cancel-failed (cancel-failed fsm)))
   ::tk/state (:lra/status lra)
   :lra lra})

(defn close!
  [lra]
  (-> (make-state lra)
      (tk/apply-signal :close)
      ((fn [fsm]
         (if (some #(= :failed-to-complete (:participant/status %)) (-> fsm :lra :lra/participants))
           (tk/apply-signal fsm :failed)
           (tk/apply-signal fsm :success))))
      :lra))

(defn cancel!
  [lra]
  (-> (make-state lra)
      (tk/apply-signal :cancel)
      ((fn [fsm]
         (if (some #(= :failed-to-compansate (:participant/status %)) (-> fsm :lra :lra/participants))
           (tk/apply-signal fsm :failed)
           (tk/apply-signal fsm :success))))
      :lra))

(comment

  (def lra
    {:lra/code "aaa"
     :lra/client-id "aaa"
     :lra/parent-id nil
     :lra/start-time 12
     :lra/finish-time 12
     :lra/status :active
     :lra/participants [{:participant/client-id "aaa"
                         :participant/top-level? false
                         :participant/status :active
                         :participant/acts [{:act/type :complete :act/url "http://localhost:6000/bbb/complete"}
                                            {:act/type :compansate :act/url "http://localhost:6000/bbb/compansate"}]}
                        {:participant/client-id "ccc"
                         :participant/top-level? false
                         :participant/status :active
                         :participant/acts [{:act/type :complete :act/url "http://localhost:5000/ccc/complete"}
                                            {:act/type :compansate :act/url "http://localhost:5000/ccc/compansate"}]}]})

  (tk/apply-signal (make-state lra) :close))
