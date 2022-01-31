(ns ayatori.ring.interface-test
  (:require [clojure.test :as test :refer :all]
            [ayatori.ring.interface :as ring_middleware]))

(deftest dummy-test
  (is (= 1 1)))
