(ns ayatori.memory-test
  (:require
   [ayatori.memory :as mem]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing]]))

(deftest sliding-window-test
  (testing "slides window when exceeding max"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window :max-messages 2}]})]
      (async/<!! (mem/add-message m {:role :user :content "1"}))
      (async/<!! (mem/add-message m {:role :assistant :content "2"}))
      (async/<!! (mem/add-message m {:role :user :content "3"}))
      (is (= 2 (count (mem/get-messages m))))
      (is (= "2" (:content (first (mem/get-messages m)))))))

  (testing "default config works"
    (let [m (mem/make-memory)]
      (async/<!! (mem/add-message m {:role :user :content "hi"}))
      (is (= 1 (count (mem/get-messages m)))))))

(deftest preserve-system-test
  (testing "preserves system messages"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window
                                            :max-messages 3
                                            :preserve-system true}]})]
      (async/<!! (mem/add-message m {:role :system :content "You are helpful"}))
      (async/<!! (mem/add-message m {:role :user :content "1"}))
      (async/<!! (mem/add-message m {:role :assistant :content "2"}))
      (async/<!! (mem/add-message m {:role :user :content "3"}))
      (async/<!! (mem/add-message m {:role :assistant :content "4"}))
      (let [msgs (mem/get-messages m)]
        (is (= 3 (count msgs)))
        (is (= :system (:role (first msgs))))))))
