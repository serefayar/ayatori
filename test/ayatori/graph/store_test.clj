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

(deftest atom-store-test
  (let [s (store/make-store)]
    (store/save-exec! s "e1" {:status :running})
    (is (= {:status :running} (store/get-exec s "e1")))
    (store/save-node-state! s "e1" :counter {:count 1})
    (is (= {:count 1} (store/get-node-state s "e1" :counter)))
    (store/delete-exec! s "e1")
    (is (nil? (store/get-exec s "e1")))))

(deftest edn-store-test
  (let [s1 (store/make-store {:type :edn :path test-path})]
    (store/save-exec! s1 "e1" {:status :done})
    (store/save-node-state! s1 "e1" :x {:v 42}))
  (let [s2 (store/make-store {:type :edn :path test-path})]
    (is (= {:status :done} (store/get-exec s2 "e1")))
    (is (= {:v 42} (store/get-node-state s2 "e1" :x)))))
