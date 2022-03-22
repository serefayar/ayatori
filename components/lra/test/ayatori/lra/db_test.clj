(ns ayatori.lra.db-test
  (:require [ayatori.lra.db :as db]
            [clojure.test :as t :refer [deftest is testing use-fixtures]]
            [java-time :as jt]
            [exoscale.ex.test]
            [datascript.core :as d]))

(def lra-code (db/uuid))

(def lra-fixture
  {:lra/code lra-code
   :lra/client-id "aaa"
   :lra/parent-id ""
   :lra/time-limit 0
   :lra/start-time (jt/instant)
   :lra/finish-time (jt/plus (jt/instant) (jt/millis 10000))
   :lra/status :active
   :lra/participants [{:participant/client-id "aaa"
                       :participant/top-level? false
                       :participant/status :active
                       :participant/acts [{:act/type :complete :act/url "http://localhost:6000/bbb/complete"}
                                          {:act/type :compensate :act/url "http://localhost:6000/bbb/compensate"}]}
                      {:participant/client-id "ccc"
                       :participant/top-level? false
                       :participant/status :active
                       :participant/acts [{:act/type :complete :act/url "http://localhost:5000/ccc/complete"}
                                          {:act/type :compensate :act/url "http://localhost:5000/ccc/compensate"}]}]})

(def ^:dynamic *db*)

(defn with-db [test-run]
  (binding [*db* (d/create-conn {:lra/code {:db/unique :db.unique/identity}})]
    (d/transact! *db* [lra-fixture])
    (test-run)))

(use-fixtures :once with-db)

(deftest save!-test
  (testing "lra save should return lra"
    (let [code (str (java.util.UUID/randomUUID))
          _ (db/save! *db* (assoc lra-fixture :lra/code code))]
      (is (= code (-> (db/find-by-code *db* code) :lra/code))))))

(deftest all-by-status-test
  (testing "should return empty sequence when no records found"
    (is (empty? (db/all-by-status *db* :cancelled))))

  (testing "should return sequence when records found"
    (let [ret (db/all-by-status *db* :active)]
      (is (seq ret))
      (is (= lra-code (-> ret first :lra/code))))))

(deftest find-by-code-test
  (testing "when no record found it should return nil"
    (is (nil? (db/find-by-code *db* "not-found"))))

  (testing "return lra by code"
    (let [ret (db/find-by-code *db* lra-code)]
      (is (= lra-code (-> ret :lra/code))))))

(deftest set-status!-test
  (testing "when no record found it throws exception"
    (is (thrown-ex-info-type? :ayatori.lra.db/generic-db-error
                              (db/set-status! *db* (db/uuid) :active))))

  (testing "when lra exists it should update status"
    (let [code (str (java.util.UUID/randomUUID))
          _ (d/transact! *db* [(assoc lra-fixture :lra/code code)])
          _ (db/set-status! *db* code :closed)]
      (is (= :closed (-> (db/find-by-code *db* code) :lra/status))))))
