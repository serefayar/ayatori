(ns ayatori.lra.core-test
  (:require [clojure.test :as t :refer [deftest is testing]]
            [ayatori.lra.db :as db]
            [ayatori.lra.core :as lra]
            [malli.core :as m]
            [ayatori.lra-domain.interface :as domain]
            [java-time :as jt]
            [ayatori.database.interface :as database]
            [exoscale.ex :as ex]
            [exoscale.ex.test]
            [clojure.core.async :as async]))

(def start-lra-fixture
  {:lra/client-id   "aaa"
   :lra/parent-code ""
   :lra/time-limit  0
   :lra/acts        [{:act/type :compensate
                      :act/url  "http://localhost:4000/aaa/compensate"}
                     {:act/type :complete
                      :act/url  "http://localhost:4000/aaa/complete"}]})

(def participant-fixture
  {:participant/client-id "aaa"
   :participant/acts      [{:act/type :compensate
                            :act/url  "http://localhost:4000/aaa/compensate"}
                           {:act/type :complete
                            :act/url  "http://localhost:4000/aaa/complete"}]})

(def lra-code (db/uuid))

(def lra-fixture
  {:lra/code         lra-code
   :lra/client-id    "aaa"
   :lra/parent-code  ""
   :lra/time-limit   0
   :lra/start-time   (jt/instant)
   :lra/finish-time  (jt/plus (jt/instant) (jt/millis 10000))
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
  (database/create {}))



;;
;; tests
;;

(deftest closable-lra?-test
  (testing "should return true with active"
    (is (true? (lra/closable-lra? lra-fixture)))))

(deftest cancelable-lra?-test
  (testing "should return true with active"
    (is (true? (lra/cancellable-lra? lra-fixture)))))

(deftest joinable-lra?-test
  (testing "should return true with active"
    (is (true? (lra/joinable-lra? lra-fixture)))))

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
    (let [ret (lra/->toplevel-participant (assoc lra-fixture
                                                 :lra/code lra-code))]
      (is (= true (m/validate domain/Participant ret)))
      (is (true? (:participant/top-level? ret)))
      (is (= :active (:participant/status ret))))))

(deftest data->participant-test
  (testing "should return a participant"
    (let [{:participant/keys [top-level? status]
           :as               ret} (lra/data->participant participant-fixture)]
      (is (= true (m/validate domain/Participant ret)))
      (is (false? top-level?))
      (is (= :active status)))))

(deftest all-lra-test
  (testing "all lra return success with or without result"
    (with-redefs [db/all-by-status (fn [_ _] [])]
      (is (seqable? (lra/all-lra database :active)))))

  (testing "all lra throw ex when db exception accours"
    (with-redefs [db/all-by-status (fn [& _]
                                     (throw (ex-info ""
                                                     {::ex/type :ayatori.lra.db/generic-db-error})))]
      (is (thrown-ex-info-type? :ayatori.lra.db/generic-db-error
                                (lra/all-lra database :active))))))

(deftest lra-by-code-test
  (testing "should throw ex when db exception accours"
    (with-redefs [db/find-by-code (fn [& _]
                                    (throw (ex-info ""
                                                    {::ex/type :ayatori.lra.db/generic-db-error})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/lra-not-found
                                (lra/lra-by-code database lra-code)))))

  (testing "should return ex if there is no result"
    (with-redefs [db/find-by-code (fn [& _] nil)]
      (is (thrown-ex-info-type? :ayatori.lra.core/lra-not-found
                                (lra/lra-by-code database lra-code)))))

  (testing "should return valid lra"
    (with-redefs [db/find-by-code (fn [& _] lra-fixture)]
      (let [ret (lra/lra-by-code database lra-code)]
        (is (true? (m/validate domain/LRA ret)))))))

(deftest new-lra!-test
  (testing "should return Fail when save fails"
    (with-redefs [db/save! (fn [& _] (throw (ex-info "boom!"
                                                     {::ex/type :ayatori.lra.db/generic-db-error})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/start-lra-failed
                                (lra/new-lra! database start-lra-fixture)))))

  (testing "should return valid lra"
    (with-redefs [db/save!        (fn [& _] lra-fixture)
                  db/find-by-code (fn [& _] lra-fixture)]
      (let [ret (lra/new-lra! database start-lra-fixture)]
        (is (true? (m/validate domain/LRA ret)))))))

(deftest start-lra!-test
  (testing "given no parent code when new-lra return fail"
    (with-redefs [lra/new-lra! (fn [& _]
                                 (throw (ex-info ""
                                                 {::ex/type :ayatori.lra.core/start-lra-failed})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/start-lra-failed
                                (lra/start-lra! database start-lra-fixture)))))

  (let [start-lra-data (assoc start-lra-fixture
                              :lra/parent-code lra-code)]

    (testing "given no parent code it should return lra-code"
      (with-redefs [lra/new-lra! (fn [& _] (assoc lra-fixture :lra/code lra-code))]
        (let [ret (lra/start-lra! database start-lra-fixture)]
          (is (= ret lra-code)))))

    (testing "given parent code when lra-by-code fails"
      (with-redefs [lra/lra-by-code (fn [& _]
                                      (throw (ex-info ""
                                                      {::ex/type :ayatori.lra.core/lra-not-found})))]
        (is (thrown-ex-info-type? :ayatori.lra.core/start-lra-failed
                                  (lra/start-lra! database start-lra-data)))))

    (testing "given parent code when new-lra return fail"
      (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                    lra/new-lra!    (fn [& _]
                                      (throw (ex-info ""
                                                      {::ex/type :ayatori.lra.core/start-lra-failed})))]
        (is (thrown-ex-info-type? :ayatori.lra.core/start-lra-failed
                                  (lra/start-lra! database start-lra-data)))))

    (with-redefs [lra/lra-by-code     (fn [& _] lra-fixture)
                  lra/new-lra!        (fn [& _] lra-fixture)
                  lra/new-nested-lra! (fn [& _]
                                        (throw (ex-info ""
                                                        {::ex/type :ayatori.lra.core/start-nested-lra-failed})))]
      (testing "given parent code when new-nested-lra! fails return Fail"
        (is (thrown-ex-info-type? :ayatori.lra.core/start-lra-failed
                                  (lra/start-lra! database start-lra-data))))

      (testing "given parent code then should return lra code"
        (with-redefs [lra/new-nested-lra! (fn [& _] {:parent-code lra-code
                                                     :lra-code    lra-code})]
          (let [ret (lra/start-lra! database start-lra-data)]
            (is (= lra-code ret))))))))

(deftest new-nested-lra!-test
  (testing "should return Fail when save fails"
    (with-redefs [lra/new-lra! (fn [& _]
                                 (throw (ex-info ""
                                                 {::ex/type :ayatori.lra.core/start-lra-failed})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/start-nested-lra-failed
                                (lra/new-nested-lra! database lra-fixture lra-fixture)))))

  (testing "should return parent and lra codes"
    (with-redefs [lra/new-lra! (fn [& _] lra-fixture)]
      (let [ret (lra/new-nested-lra! database lra-fixture (assoc lra-fixture :lra/code lra-code))]
        (is (= (:parent-code ret) (:lra/code lra-fixture)))
        (is (= (:lra-code ret) lra-code))))))

(deftest join!-test
  (testing "given lra code and participant if lra not exists it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] (throw (ex-info "not exists" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/join-lra-failed
                                (lra/join! database "" participant-fixture)))))

  (testing "given lra code and participant when save fails it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/save!        (fn [& _] (throw (ex-info "boom!" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/join-lra-failed
                                (lra/join! database "" participant-fixture)))))

  (testing "given lra and participant it should return lra"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/save!        (fn [& _] lra-fixture)]
      (is (= (:lra/code lra-fixture) (lra/join! database
                                                (:lra/code lra-fixture)
                                                participant-fixture))))))

(deftest close-lra!-test
  (testing "given lra code if lra not exists it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] (throw (ex-info "not exists" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/close-lra-failed
                                (lra/close-lra! database (async/chan) lra-code)))))

  (testing "given lra code when set status fails it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/set-status!  (fn [& _] (throw (ex-info "boom!" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/close-lra-failed
                                (lra/close-lra! database (async/chan) lra-code)))))

  (testing "given lra code when set status succes it should return lra code"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/set-status!  (fn [& _] {})]
      (let [ret (lra/close-lra! database (async/chan) lra-code)]
        (is (= ret lra-code))))))

(deftest cancel-lra!-test
  (testing "given lra code if lra not exists it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] (throw (ex-info "not exists" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/cancel-lra-failed
                                (lra/cancel-lra! database (async/chan) lra-code)))))

  (testing "given lra code when set status fails it should return Fail"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/set-status!  (fn [& _] (throw (ex-info "boom!" {})))]
      (is (thrown-ex-info-type? :ayatori.lra.core/cancel-lra-failed
                                (lra/cancel-lra! database (async/chan) lra-code)))))

  (testing "given lra code when set status succes it should return lra code"
    (with-redefs [lra/lra-by-code (fn [& _] lra-fixture)
                  db/set-status!  (fn [& _] {})]
      (let [ret (lra/cancel-lra! database (async/chan) lra-code)]
        (is (= ret lra-code))))))
