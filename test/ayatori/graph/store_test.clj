(ns ayatori.graph.store-test
  (:require
   [ayatori.graph.store :as store]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(def ^:private test-path "target/test-store.edn")

(use-fixtures :each
  (fn [f]
    (io/delete-file test-path true)
    (f)
    (io/delete-file test-path true)))

(defn- run-store-tests [store-name store]
  (testing (str store-name ": exec CRUD")
    (store/save-exec! store "e1" {:status :running :node :a})
    (is (= {:status :running :node :a} (store/get-exec store "e1")))
    (store/delete-exec! store "e1")
    (is (nil? (store/get-exec store "e1"))))

  (testing (str store-name ": node state scoped by exec-id")
    (store/save-node-state! store "e1" :counter {:count 1})
    (store/save-node-state! store "e2" :counter {:count 99})
    (is (= {:count 1} (store/get-node-state store "e1" :counter)))
    (is (= {:count 99} (store/get-node-state store "e2" :counter))))

  (testing (str store-name ": delete-exec cleans node state")
    (store/save-node-state! store "e3" :a {:v 1})
    (store/save-exec! store "e3" {:status :running})
    (store/delete-exec! store "e3")
    (is (nil? (store/get-node-state store "e3" :a)))
    (is (nil? (store/get-exec store "e3")))))

(deftest atom-store-test
  (run-store-tests "AtomStore" (store/make-store)))

(deftest edn-store-test
  (run-store-tests "EdnStore" (store/make-store {:type :edn :path test-path})))

(deftest edn-store-persistence-test
  (testing "EdnStore survives recreation (file-based persistence)"
    (let [s1 (store/make-store {:type :edn :path test-path})]
      (store/save-exec! s1 "e1" {:status :done})
      (store/save-node-state! s1 "e1" :x {:v 42}))
    (let [s2 (store/make-store {:type :edn :path test-path})]
      (is (= {:status :done} (store/get-exec s2 "e1")))
      (is (= {:v 42} (store/get-node-state s2 "e1" :x))))))
