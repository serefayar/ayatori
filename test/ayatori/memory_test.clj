(ns ayatori.memory-test
  (:require
   [ayatori.memory :as mem]
   [clojure.test :refer [deftest is testing]]))

(deftest sliding-window-test
  (testing "slides window when exceeding max"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window :max-messages 2}]})]
      (mem/add-message m {:role :user :content "1"})
      (mem/add-message m {:role :assistant :content "2"})
      (mem/add-message m {:role :user :content "3"})
      (is (= 2 (count (mem/get-messages m))))
      (is (= "2" (:content (first (mem/get-messages m)))))))

  (testing "default config works"
    (let [m (mem/make-memory)]
      (mem/add-message m {:role :user :content "hi"})
      (is (= 1 (count (mem/get-messages m))))))

  (testing "clear! removes all messages"
    (let [m (mem/make-memory)]
      (mem/add-message m {:role :user :content "hi"})
      (mem/clear! m)
      (is (= 0 (count (mem/get-messages m)))))))

(deftest preserve-system-test
  (testing "preserves system messages when preserve-system true"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window
                                            :max-messages 3
                                            :preserve-system true}]})]
      (mem/add-message m {:role :system :content "You are helpful"})
      (mem/add-message m {:role :user :content "1"})
      (mem/add-message m {:role :assistant :content "2"})
      (mem/add-message m {:role :user :content "3"})
      (mem/add-message m {:role :assistant :content "4"})
      (let [msgs (mem/get-messages m)]
        (is (= 3 (count msgs)))
        (is (= :system (:role (first msgs))))
        (is (= "You are helpful" (:content (first msgs)))))))

  (testing "does not preserve system when preserve-system false"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window
                                            :max-messages 2
                                            :preserve-system false}]})]
      (mem/add-message m {:role :system :content "You are helpful"})
      (mem/add-message m {:role :user :content "1"})
      (mem/add-message m {:role :assistant :content "2"})
      (let [msgs (mem/get-messages m)]
        (is (= 2 (count msgs)))
        (is (= :user (:role (first msgs))))))))
