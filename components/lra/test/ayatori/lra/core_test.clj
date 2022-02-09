(ns ayatori.lra.core-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [ayatori.lra.db :as db]
            [ayatori.lra.core :as lra]
            [malli.core :as m]
            [ayatori.lra.domain :as domain]
            [java-time :as jt]
            [fmnoise.flow :as flow]
            [ayatori.lra-engine.interface :as engine]))

(def start-lra-fixture
  {:lra/client-id "aaa"
   :lra/parent-code ""
   :lra/time-limit 0
   :lra/acts [{:act/type :compansate
               :act/url "http://localhost:4000/aaa/compansate"}
              {:act/type :complete
               :act/url "http://localhost:4000/aaa/complete"}]})

(def participant-fixture
  {:participant/client-id "aaa"
   :participant/acts [{:act/type :compansate
                       :act/url "http://localhost:4000/aaa/compansate"}
                      {:act/type :complete
                       :act/url "http://localhost:4000/aaa/complete"}]})

(def lra-fixture
  {:lra/code (str (java.util.UUID/randomUUID))
   :lra/client-id "aaa"
   :lra/parent-id nil
   :lra/time-limit 0
   :lra/start-time (jt/instant)
   :lra/finish-time (jt/plus (jt/instant) (jt/millis 10000))
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

;;
;; tests
;;

(deftest closable-lra-test
  (testing "should return true with active"
    (let [lra {:lra/status :active}]
      (is (true? (lra/closable-lra? lra))))))

(deftest cancelable-lra-test
  (testing "should return true with active"
    (let [lra {:lra/status :active}]
      (is (true? (lra/cancellable-lra? lra))))))

(deftest joinable-lra-test
  (testing "should return true with active"
    (let [lra {:lra/status :active}]
      (is (true? (lra/joinable-lra? lra))))))

(deftest data->lra-test
  (testing "if time limit 0 return valid lra"
    (let [ret (lra/data->lra start-lra-fixture)]
      (is (true? (m/validate domain/LRA ret)))))
  (testing "if time limit > 0 return valid lra"
    (let [ret (lra/data->lra (assoc start-lra-fixture
                                    :lra/time-limit 100))]
      (is (true? (m/validate domain/LRA ret))))))

(deftest ->toplevel-participant-test
  (testing "should return valid top level participant"
    (let [ret (lra/->toplevel-participant (assoc start-lra-fixture
                                                 :lra/code (str (java.util.UUID/randomUUID))))]
      (is (= true (m/validate domain/TopLevelParticipant ret)))
      (is (true? (:participant/top-level? ret)))
      (is (= :active (:participant/status ret))))))

(deftest data->participant-test
  (testing "should return a participant"
    (let [{:participant/keys [top-level? status] :as ret} (lra/data->participant participant-fixture)]
      (is (= true (m/validate domain/Participant ret)))
      (is (false? top-level?))
      (is (= :active status)))))

(deftest all-lra-test
  (testing "all lra return success with or without result"
    (with-redefs [db/all-by-status (fn [_ _] [])]
      (is (seqable? (lra/all-lra nil :active)))))

  (testing "all lra return Fail when db exception accours"
    (with-redefs [db/all-by-status (fn [& _] (throw (ex-info "db error" {})))]
      (is (instance? fmnoise.flow.Fail (lra/all-lra {} :active)))))

  (with-redefs [db/all-by-status (fn [_ _] nil)]
    (testing "nil return from db"
      (is (seqable? (lra/all-lra nil :active))))))

(deftest lra-by-code-test
  (testing "should return Fail when db exception accours"
    (with-redefs [db/find-by-code (fn [& _] (throw (ex-info "db error" {})))]
      (let [ret (lra/lra-by-code nil nil)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :generic-error (-> ret ex-data :type))))))

  (testing "should return Fail if there is no result"
    (with-redefs [db/find-by-code (fn [& _] nil)]
      (let [ret (lra/lra-by-code nil nil)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :resource-not-found (-> ret ex-data :type))))))

  (testing "should return valid lra"
    (with-redefs [db/find-by-code (fn [& _] lra-fixture)]
      (let [ret (lra/lra-by-code nil nil)]
        (is (true? (m/validate domain/LRA ret)))))))

(deftest new-lra!-test
  (testing "should return Fail when save fails"
    (with-redefs [db/save! (fn [& _] (throw (ex-info "boom!" {})))]
      (let [ret (lra/new-lra! nil lra-fixture)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :start-lra-failed (-> ret ex-data :type))))))

  (testing "should return valid lra"
    (with-redefs [db/save! (fn [& _] lra-fixture)
                  db/find-by-code (fn [& _] lra-fixture)]
      (let [ret (lra/new-lra! nil lra-fixture)]
        (is (true? (m/validate domain/LRA ret)))))))

(deftest new-nested-lra!-test
  (testing "should return Fail when save fails"
    (with-redefs [lra/new-lra! (fn [& _] (throw (ex-info "boom!" {})))]
      (let [ret (lra/new-nested-lra! {} lra-fixture lra-fixture)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :start-nested-lra-failed (-> ret ex-data :type))))))

  (testing "should return parent and lra codes"
    (with-redefs [lra/new-lra! (fn [& _] lra-fixture)]
      (let [lra-code (str (java.util.UUID/randomUUID))
            ret (lra/new-nested-lra! {} lra-fixture (assoc lra-fixture :lra/code lra-code))]
        (is (= (:parent-code ret) (:lra/code lra-fixture)))
        (is (= (:lra-code ret) lra-code))))))

(deftest start-lra!-test
  (testing "given no parent code when new-lra return fail"
    (with-redefs [lra/new-lra! (fn [& _] (flow/fail-with {}))]
      (is (instance? fmnoise.flow.Fail (lra/start-lra! {} start-lra-fixture)))))

  (let [lra-code (str (java.util.UUID/randomUUID))
        start-lra-data (assoc start-lra-fixture
                              :lra/parent-code lra-code)]

    (testing "given no parent code it should return lra-code"
      (with-redefs [lra/new-lra! (fn [& _] (assoc lra-fixture :lra/code lra-code))]
        (let [ret (lra/start-lra! {} start-lra-fixture)]
          (is (= ret lra-code)))))

    (testing "given parent code when lra-by-code fails"
      (with-redefs [lra/lra-by-code (fn [& _] (flow/fail-with {}))]
        (let [ret (lra/start-lra! {} start-lra-data)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :start-lra-failed (-> ret ex-data :type))))))

    (testing "given parent code when new-lra return fail"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    lra/new-lra! (flow/fail-with {})]
        (let [ret (lra/start-lra! {} start-lra-data)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :start-lra-failed (-> ret ex-data :type))))))

    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  lra/new-lra! (fn [& _] lra-fixture)
                  lra/new-nested-lra! (fn [& _] (flow/fail-with {}))]
      (testing "given parent code when new-nested-lra! fails return Fail"
        (let [ret (lra/start-lra! {} start-lra-data)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :start-lra-failed (-> ret ex-data :type)))))

      (testing "given parent code then should return lra code"
        (with-redefs [lra/new-nested-lra! (fn [& _] {:parent-code lra-code :lra-code lra-code})]
          (let [ret (lra/start-lra! {} start-lra-data)]
            (is (= lra-code ret))))))))

(deftest join!-test
  (testing "given lra code and participant if lra not exists it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _])]
      (let [ret (lra/join! {} "" participant-fixture)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :resource-not-found (-> ret ex-data :type))))))

  (testing "given lra code and participant when save fails it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/save! (fn [& _] (throw (ex-info "boom!" {})))]
      (let [ret (lra/join! {} "" participant-fixture)]
        (is (instance? fmnoise.flow.Fail ret))
        (is (= :resource-not-found (-> ret ex-data :type))))))

  (testing "given lra and participant it should return lra"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/save! (fn [& _] lra-fixture)]
      (is (= (:lra/code lra-fixture) (lra/join! {} (:lra/code lra-fixture) participant-fixture))))))

(deftest close!-test
  (let [lra-code (str (java.util.UUID/randomUUID))]
    (with-redefs [engine/close! (fn [_] lra-fixture)
                  db/save! (fn [& _] lra-code)]
      (is (= lra-code @(lra/close! {} lra-fixture))))))

(deftest close-lra!-test
  (let [lra-code (str (java.util.UUID/randomUUID))]
    (testing "given lra code if lra not exists it should return Fail"
      (with-redefs [lra/lra-by-code (fn [& _])]
        (let [ret (lra/close-lra! {} lra-code)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :resource-not-found (-> ret ex-data :type))))))

    (testing "given lra code when set status fails it should return Fail"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    db/set-status! (fn [& _] (throw (ex-info "boom!" {})))]
        (let [ret (lra/close-lra! {} lra-code)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :resource-not-found (-> ret ex-data :type))))))

    (testing "given lra code when set status succes it should return lra code"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    db/set-status! (fn [& _] {})]
        (let [ret (lra/close-lra! {} lra-code)]
          (is (= ret lra-code)))))))

(deftest cancel!-test
  (let [lra-code (str (java.util.UUID/randomUUID))]
    (with-redefs [engine/cancel! (fn [_] lra-fixture)
                  db/save! (fn [& _] lra-code)]
      (is (= lra-code @(lra/close! {} lra-fixture))))))

(deftest cancel-lra!-test
  (let [lra-code (str (java.util.UUID/randomUUID))]
    (testing "given lra code if lra not exists it should return Fail"
      (with-redefs [lra/lra-by-code (fn [& _])]
        (let [ret (lra/cancel-lra! {} lra-code)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :resource-not-found (-> ret ex-data :type))))))

    (testing "given lra code when set status fails it should return Fail"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    db/set-status! (fn [& _] (throw (ex-info "boom!" {})))]
        (let [ret (lra/cancel-lra! {} lra-code)]
          (is (instance? fmnoise.flow.Fail ret))
          (is (= :resource-not-found (-> ret ex-data :type))))))

    (testing "given lra code when set status succes it should return lra code"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    db/set-status! (fn [& _] {})]
        (let [ret (lra/cancel-lra! {} lra-code)]
          (is (= ret lra-code)))))))
