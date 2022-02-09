(ns ayatori.lra-engine.core-test
  (:require [clojure.test :as test :refer [deftest is testing]]
            [ayatori.lra-engine.core :as engine]))

(def lra-code (str (java.util.UUID/randomUUID)))

(def lra-fixture
  {:lra/code lra-code
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
                                       :not-found)]
      (is (nil? (:act/type ret)))))

  (testing "given nil or empty participant seq and act/type if act/type not found it should return nil"
    (let [ret (engine/find-by-act-type nil
                                       :not-found)]
      (is (nil? (:act/type ret))))))

;;
;; cancel tests
;;

(deftest compansate-participant!-test
  (let [participant (-> lra-fixture :lra/participants first)]
    (testing "when act type not found return participiant without change status"
      (with-redefs [engine/find-by-act-type (fn [& _] nil)]
        (let [ret (engine/compansate-participant! lra-code participant)]
          (is (= (:participant/status participant) (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status failed"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _] (throw (ex-info "boom!" {})))]
        (let [ret (engine/compansate-participant! lra-code participant)]
          (is (= :failed-to-compansate (:participant/status ret))))))

    (testing "when act type found but http request fails return participiant with status compansated"
      (with-redefs [engine/find-by-act-type (fn [& _] participant)
                    engine/http-request! (fn [& _])]
        (let [ret (engine/compansate-participant! lra-code participant)]
          (is (= :compansated (:participant/status ret))))))))

(deftest compansate!-test
  (testing "given lra-code and empty seq it should return empty seq"
    (is (empty? (engine/compansate! lra-code []))))

  (testing "given lra-code and participants it should return updated participants"
    (let [participants (-> lra-fixture :lra/participants)
          ret (engine/compansate! lra-code participants)]
      (is (not= participants ret))
      (is (every? #(some #{%} [:compansated :failed-to-compansate])
                  (map :participant/status ret))))))

(deftest cancel-lra!-test
  (testing "it should return lra participants"
    (with-redefs [engine/compansate! (fn [_ p] p)]
      (let [fsm (engine/make-state lra-fixture)
            ret (engine/cancel-lra! fsm)]
        (is (= (:lra/participants lra-fixture) (-> ret :lra :lra/participants)))))))

(deftest cancel-succes-test
  (testing "it should return cancelled lra status"
    (is (= :cancelled (-> (engine/cancel-success (engine/make-state lra-fixture))
                          :lra
                          :lra/status)))))

(deftest cancel-failed-test
  (testing "it should return failed to cancel lra status"
    (is (= :failed-to-cancel (-> (engine/cancel-failed (engine/make-state lra-fixture))
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
    (is (empty? (engine/complete! lra-code []))))

  (testing "given lra-code and participants it should return updated participants"
    (let [participants (-> lra-fixture :lra/participants)
          ret (engine/complete! lra-code participants)]
      (is (not= participants ret))
      (is (every? #(some #{%} [:completed :failed-to-complete])
                  (map :participant/status ret))))))

(deftest close-lra!-test
  (testing "it should return lra participants"
    (with-redefs [engine/complete! (fn [_ p] p)]
      (let [fsm (engine/make-state lra-fixture)
            ret (engine/close-lra! fsm)]
        (is (= (:lra/participants lra-fixture) (-> ret :lra :lra/participants)))))))

(deftest close-succes-test
  (testing "it should return closed lra status"
    (is (= :closed (-> (engine/close-success (engine/make-state lra-fixture))
                       :lra
                       :lra/status)))))

(deftest close-failed-test
  (testing "it should return failed to close lra status"
    (is (= :failed-to-close (-> (engine/close-failed (engine/make-state lra-fixture))
                                :lra
                                :lra/status)))))

;;
;; state tests
;;

(deftest makestate-test)

(deftest close!-test)

(deftest cencal!-test)
