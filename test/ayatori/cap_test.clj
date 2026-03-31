(ns ayatori.cap-test
  (:require
   [ayatori.cap :as cap]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(deftest parse-uri-test
  (testing "parses valid URI"
    (is (= {:host "localhost" :port 9000 :agent :calculator :cap :compute}
           (cap/parse-uri "ayatori://localhost:9000/c/calculator/compute"))))
  (testing "throws on invalid scheme"
    (is (thrown-with-msg? Exception #"Invalid scheme"
                          (cap/parse-uri "http://localhost:9000/c/calc/main"))))
  (testing "throws on invalid path"
    (is (thrown-with-msg? Exception #"Invalid path"
                          (cap/parse-uri "ayatori://localhost:9000/bad/path"))))
  (testing "throws on malformed URI"
    (is (thrown? Exception (cap/parse-uri "not a uri at all")))))

(deftest make-uri-test
  (testing "constructs URI from components"
    (is (= "ayatori://localhost:9000/c/calc/main"
           (cap/make-uri "localhost" 9000 :calc :main)))
    (is (= "ayatori://node-2:8000/c/processor/query"
           (cap/make-uri "node-2" 8000 "processor" "query")))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defspec uri-roundtrip 100
  (prop/for-all [host (gen/not-empty gen/string-alphanumeric)
                 port (gen/choose 1 65535)
                 agent-name (gen/not-empty gen/string-alphanumeric)
                 cap-name (gen/not-empty gen/string-alphanumeric)]
                (let [uri (cap/make-uri host port agent-name cap-name)
                      parsed (cap/parse-uri uri)]
                  (and (= host (:host parsed))
                       (= port (:port parsed))
                       (= (keyword agent-name) (:agent parsed))
                       (= (keyword cap-name) (:cap parsed))))))
