(ns ayatori.lra-engine.interface
  (:require
   [tilakone.core :as tk]
   [ayatori.lra-engine.core :as engine]))

(defn close!
  [lra]
  (-> (engine/make-state lra)
      (tk/apply-signal :close)
      ((fn [fsm]
         (if (some #(= :failed-to-complete (:participant/status %)) (-> fsm :lra :lra/participants))
           (tk/apply-signal fsm :failed)
           (tk/apply-signal fsm :success))))
      :lra))

(defn cancel!
  [lra]
  (-> (engine/make-state lra)
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

  (tk/apply-signal (engine/make-state lra) :close))
