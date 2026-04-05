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
      (is (= 1 (count (mem/get-messages m))))))

  (testing "clear! removes all messages"
    (let [m (mem/make-memory)]
      (async/<!! (mem/add-message m {:role :user :content "hi"}))
      (mem/clear! m)
      (is (= 0 (count (mem/get-messages m)))))))

(deftest preserve-system-test
  (testing "preserves system messages when preserve-system true"
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
        (is (= :system (:role (first msgs))))
        (is (= "You are helpful" (:content (first msgs)))))))

  (testing "does not preserve system when preserve-system false"
    (let [m (mem/make-memory {:strategies [{:type :sliding-window
                                            :max-messages 2
                                            :preserve-system false}]})]
      (async/<!! (mem/add-message m {:role :system :content "You are helpful"}))
      (async/<!! (mem/add-message m {:role :user :content "1"}))
      (async/<!! (mem/add-message m {:role :assistant :content "2"}))
      (let [msgs (mem/get-messages m)]
        (is (= 2 (count msgs)))
        (is (= :user (:role (first msgs))))))))

(deftest token-budget-test
  (testing "removes messages when exceeding token budget"
    (let [m (mem/make-memory {:strategies [{:type :token-budget :max-tokens 10}]})]
      (async/<!! (mem/add-message m {:role :user :content "1234"}))
      (async/<!! (mem/add-message m {:role :assistant :content "5678"}))
      (async/<!! (mem/add-message m {:role :user :content "this is a very long message that exceeds budget"}))
      (let [msgs (mem/get-messages m)]
        (is (= 1 (count msgs)))
        (is (= :user (:role (first msgs)))))))

  (testing "preserves system messages with token budget"
    (let [m (mem/make-memory {:strategies [{:type :token-budget
                                            :max-tokens 20
                                            :preserve-system true}]})]
      (async/<!! (mem/add-message m {:role :system :content "sys"}))
      (async/<!! (mem/add-message m {:role :user :content "this is a long message"}))
      (async/<!! (mem/add-message m {:role :assistant :content "another long response here"}))
      (let [msgs (mem/get-messages m)]
        (is (= :system (:role (first msgs)))))))

  (testing "custom count function"
    (let [m (mem/make-memory {:strategies [{:type :token-budget
                                            :max-tokens 5
                                            :count-fn (constantly 2)}]})]
      (async/<!! (mem/add-message m {:role :user :content "a"}))
      (async/<!! (mem/add-message m {:role :user :content "b"}))
      (async/<!! (mem/add-message m {:role :user :content "c"}))
      (is (= 2 (count (mem/get-messages m)))))))

(deftest summary-test
  (testing "does not summarize below threshold"
    (let [mock-invoke (fn [_ _] (async/go {:content "Should not be called"}))
          m (mem/make-memory {:strategies [{:type :summary
                                            :threshold 5
                                            :invoke-fn mock-invoke}]})]
      (dotimes [i 4]
        (async/<!! (mem/add-message m {:role :user :content (str i)})))
      (is (= 4 (count (mem/get-messages m))))))

  (testing "summarizes when threshold exceeded"
    (let [mock-invoke (fn [_ _] (async/go {:content "This is a summary"}))
          m (mem/make-memory {:strategies [{:type :summary
                                            :threshold 4
                                            :keep-recent 2
                                            :invoke-fn mock-invoke}]})]
      (dotimes [i 5]
        (async/<!! (mem/add-message m {:role :user :content (str "message " i)})))
      (let [msgs (mem/get-messages m)]
        (is (= 3 (count msgs)))
        (is (clojure.string/includes? (:content (first msgs)) "Summary")))))

  (testing "keeps original messages on LLM error"
    (let [mock-invoke (fn [_ _] (async/go (ex-info "LLM error" {})))
          m (mem/make-memory {:strategies [{:type :summary
                                            :threshold 3
                                            :invoke-fn mock-invoke}]})]
      (dotimes [i 4]
        (async/<!! (mem/add-message m {:role :user :content (str i)})))
      (is (= 4 (count (mem/get-messages m)))))))
