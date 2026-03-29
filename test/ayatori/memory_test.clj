(ns ayatori.memory-test
  (:require
   [ayatori.memory :as mem]
   [clojure.test :refer [deftest is testing]]))

(deftest sliding-window-test
  (testing "slides window when exceeding max"
    (let [m (mem/make-memory {:type :sliding-window :max-messages 2})]
      (mem/add-message m {:role :user :content "1"})
      (mem/add-message m {:role :assistant :content "2"})
      (mem/add-message m {:role :user :content "3"})
      (is (= 2 (count (mem/get-messages m))))
      (is (= "2" (:content (first (mem/get-messages m))))))))
