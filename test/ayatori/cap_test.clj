(ns ayatori.cap-test
  (:require
   [ayatori.cap :as cap]
   [clojure.test :refer [deftest is testing]]
   [clojure.test.check.clojure-test :refer [defspec]]
   [clojure.test.check.generators :as gen]
   [clojure.test.check.properties :as prop]))

(deftest parse-uri-test
  (testing "parses valid URI"
    (is (= {:host "localhost" :port 9000 :ref "abc123"}
           (cap/parse-uri "ayatori://localhost:9000/c/abc123"))))
  (testing "throws on invalid scheme"
    (is (thrown-with-msg? Exception #"Invalid scheme"
                          (cap/parse-uri "http://localhost:9000/c/abc123"))))
  (testing "throws on invalid path"
    (is (thrown-with-msg? Exception #"Invalid path"
                          (cap/parse-uri "ayatori://localhost:9000/bad/path"))))
  (testing "throws on malformed URI"
    (is (thrown? Exception (cap/parse-uri "not a uri at all")))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defspec uri-roundtrip 100
  (prop/for-all [host (gen/not-empty gen/string-alphanumeric)
                 port (gen/choose 1 65535)
                 ref  (gen/not-empty gen/string-alphanumeric)]
                (let [uri    (cap/make-uri host port ref)
                      parsed (cap/parse-uri uri)]
                  (and (= host (:host parsed))
                       (= port (:port parsed))
                       (= ref (:ref parsed))))))
