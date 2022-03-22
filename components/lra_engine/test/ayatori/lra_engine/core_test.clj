(ns ayatori.lra-engine.core-test
  (:require [clojure.test :as test :refer [deftest is testing]]
            [ayatori.lra-engine.core :as engine]
            [java-time :as jt]
            [malli.core :as m]
            [ayatori.lra-domain.interface :as domain]
            [ayatori.lra.db :as db]
            [ayatori.database.interface :as database]))

(def lra-code (db/uuid))

(def nested-lra-code (db/uuid))

(def lra-fixture
  {:lra/code         lra-code
   :lra/client-id    "aaa"
   :lra/parent-id    ""
   :lra/start-time   (jt/instant)
   :lra/finish-time  (jt/plus (jt/instant) (jt/days 1))
   :lra/time-limit   0
   :lra/status       :active
   :lra/participants [{:participant/client-id  "aaa"
                       :participant/top-level? false
                       :participant/status     :active
                       :participant/acts       [{:act/type :complete
                                                 :act/url  "http://localhost:6000/bbb/complete"}
                                                {:act/type :compensate
                                                 :act/url  "http://localhost:6000/bbb/compensate"}]}
                      {:participant/client-id  "bbb"
                       :participant/top-level? true
                       :participant/status     :active
                       :participant/lra-code   nested-lra-code}
                      {:participant/client-id  "ccc"
                       :participant/top-level? false
                       :participant/status     :active
                       :participant/acts       [{:act/type :complete
                                                 :act/url  "http://localhost:5000/ccc/complete"}
                                                {:act/type :compensate
                                                 :act/url  "http://localhost:5000/ccc/compensate"}]}]})


(def nested-lra-fixture
  {:lra/code         nested-lra-code
   :lra/client-id    "bbb"
   :lra/parent-id    lra-code
   :lra/start-time   (jt/instant)
   :lra/finish-time  (jt/plus (jt/instant) (jt/days 1))
   :lra/time-limit   0
   :lra/status       :active
   :lra/participants [{:participant/client-id  "aaa"
                       :participant/top-level? false
                       :participant/status     :active
                       :participant/acts       [{:act/type :complete
                                                 :act/url  "http://localhost:6000/bbb/complete"}
                                                {:act/type :compensate
                                                 :act/url  "http://localhost:6000/bbb/compensate"}]}
                      {:participant/client-id  "ccc"
                       :participant/top-level? false
                       :participant/status     :active
                       :participant/acts       [{:act/type :complete
                                                 :act/url  "http://localhost:5000/ccc/complete"}
                                                {:act/type :compensate
                                                 :act/url  "http://localhost:5000/ccc/compensate"}]}]})


(def database
  (database/create {:datasource (atom {})}))

;;
;; tests
;;

(deftest find-by-act-type-test
  (testing "given participants and act/type if act/type found it should return act"
    (let [ret (engine/find-by-act-type (-> lra-fixture
                                           :lra/participants
                                           first)
                                       :complete)]
      (is (= :complete (:act/type ret)))))

  (testing "given participants and act/type if act/type not found it should return nil"
    (let [ret (engine/find-by-act-type (-> lra-fixture
                                           :lra/participants
                                           first)
                                       :status)]
      (is (nil? (:act/type ret))))))

;;
;; cancel tests
;;

(deftest compensate-participant!-test
  (let [participant (-> lra-fixture :lra/participants first)]
    (testing "when act type not found return participiant without change status"
      (with-redefs [engine/find-by-act-type (fn [& _] nil)]
        (let [ret (engine/compensate-participant! lra-code participant)]
          (is (= (:participant/status participant) (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status failed"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _] (throw (ex-info "boom!" {})))]
        (let [ret (engine/compensate-participant! lra-code participant)]
          (is (= :failed-to-compensate (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status compensated"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _])]
        (let [ret (engine/compensate-participant! lra-code participant)]
          (is (= :compensated (:participant/status ret))))))))

(deftest compensate!-test
  (testing "given lra-code and empty seq it should return empty seq"
    (is (empty? (engine/compensate! database lra-code []))))

  (testing "given lra-code and participants it should return updated participants"
    (with-redefs [db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [participants (-> lra-fixture :lra/participants)
            ret (engine/compensate! database lra-code participants)]
        (is (not= participants ret))
        (is (every? #(some #{%} [:compensated :active :failed-to-compensate])
                    (map :participant/status ret)))))
    ))

(deftest cancel-lra!-test
  (testing "it should return lra participants"
    (with-redefs [engine/compensate! (fn [_ _ p] p)]
      (let [fsm (engine/make-state database lra-fixture)
            ret (engine/cancel-lra! fsm)]
        (is (= (:lra/participants lra-fixture) (-> ret :lra :lra/participants)))))))

(deftest cancel-succes-test
  (testing "it should return cancelled lra status"
    (is (= :cancelled (-> (engine/cancel-success (engine/make-state database lra-fixture))
                          :lra
                          :lra/status)))))

(deftest cancel-failed-test
  (testing "it should return failed to cancel lra status"
    (is (= :failed-to-cancel (-> (engine/cancel-failed (engine/make-state database lra-fixture))
                                 :lra
                                 :lra/status)))))

;;
;; close tests
;;

(deftest complete-participant!-test
  (let [participant (-> lra-fixture :lra/participants first)]
    (testing "when act type not found return participiant without change status"
      (with-redefs [engine/find-by-act-type (fn [& _] nil)]
        (let [ret (engine/complete-participant! lra-code participant)]
          (is (= (:participant/status participant) (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status failed"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _] (throw (ex-info "boom!" {})))]
        (let [ret (engine/complete-participant! lra-code participant)]
          (is (= :failed-to-complete (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status completed"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _])]
        (let [ret (engine/complete-participant! lra-code participant)]
          (is (= :completed (:participant/status ret))))))))

(deftest complete!-test
  (testing "given lra-code and empty seq it should return empty seq"
    (is (empty? (engine/complete! database lra-code []))))

  (testing "given lra-code and participants it should return updated participants"
    (with-redefs [db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [participants (-> lra-fixture :lra/participants)
            ret (engine/complete! database lra-code participants)]
        (is (not= participants ret))
        (is (every? #(some #{%} [:completed :failed-to-complete])
                    (map :participant/status ret)))))))

(deftest close-lra!-test
  (testing "it should return lra participants"
    (with-redefs [engine/complete! (fn [_ _ p] p)]
      (let [fsm (engine/make-state database lra-fixture)
            ret (engine/close-lra! fsm)]
        (is (= (:lra/participants lra-fixture) (-> ret :lra :lra/participants)))))))

(deftest close-succes-test
  (testing "it should return closed lra status"
    (is (= :closed (-> (engine/close-success (engine/make-state database lra-fixture))
                       :lra
                       :lra/status)))))

(deftest close-failed-test
  (testing "it should return failed to close lra status"
    (is (= :failed-to-close (-> (engine/close-failed (engine/make-state database lra-fixture))
                                :lra
                                :lra/status)))))

;;
;; state tests
;;

(deftest make-state-test
  (testing "it should return valid state"
    (let [ret (engine/make-state database lra-fixture)]
      (is (= lra-fixture (:lra ret)))
      (is (m/validate engine/EngineState ret))
      (is (= (:lra/status lra-fixture) (:tilakone.core/state ret))))))

(deftest participant-by-status-test
  (testing "should return valid participant when status found"
    (let [ret (engine/participant-by-status :active (:lra/participants lra-fixture))]
      (is (seq ret))
      (is (every? #(m/validate domain/Participant %) ret))))

  (testing "should return empty seq when status not found"
    (let [ret (engine/participant-by-status :failed-to-complete (:lra/participants lra-fixture))]
      (is (empty? ret)))))

(deftest close!-test
  (testing "should return lra with :failed-to-close status when any of the participants are failed to complete"
    (with-redefs [engine/participant-by-status (fn [_ _] '({:participant/status :failed-to-complete}))
                  db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [ret (engine/close! database lra-fixture)]
        (is (m/validate domain/LRA ret))
        (is (= :failed-to-close (:lra/status ret))))))

  (testing "should return lra with :closed status when all of the participants are completed"
    (with-redefs [engine/participant-by-status (fn [_ _] '())
                  db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [ret (engine/close! database lra-fixture)]
        (is (m/validate domain/LRA ret))
        (is (= :closed (:lra/status ret)))))))

(deftest cancel!-test
  (testing "should return lra with :failed-to-cancel status when any of the participants are failed to compensate"
    (with-redefs [engine/participant-by-status (fn [_ _] '({:participant/status :failed-to-compensate}))
                  db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [ret (engine/cancel! database lra-fixture)]
        (is (m/validate domain/LRA ret))
        (is (= :failed-to-cancel (:lra/status ret))))))

  (testing "should return lra with :cancelled status when all of the participants are compensated"
    (with-redefs [engine/participant-by-status (fn [_ _] '())
                  db/find-by-code (fn [_ _] nested-lra-fixture)]
      (let [ret (engine/cancel! database lra-fixture)]
        (is (m/validate domain/LRA ret))
        (is (= :cancelled (:lra/status ret)))))))
