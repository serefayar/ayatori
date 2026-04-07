(ns ayatori.llm.provider-test
  (:require
   [ayatori.llm.provider :as p]
   [ayatori.llm.provider.anthropic :as anthropic]
   [ayatori.llm.provider.ollama :as ollama]
   [ayatori.llm.provider.openai :as openai]
   [clojure.test :refer [deftest is testing]]))

(def ollama (ollama/make-ollama-provider {:model "llama3" :base-url "http://localhost:11434"}))
(def openai (openai/make-openai-provider {:model "gpt-4o" :api-key "sk-test"}))
(def anthropic (anthropic/make-anthropic-provider {:model "claude-sonnet-4-20250514" :api-key "sk-ant-test"}))

(deftest ollama-request-test
  (let [{:keys [url body]} (p/build-request ollama {:messages [{:role :user :content "hi"}]})]
    (is (= "http://localhost:11434/v1/chat/completions" url))
    (is (= "llama3" (:model body)))))

(deftest openai-auth-test
  (let [{:keys [headers]} (p/build-request openai {:messages [{:role :user :content "hi"}]})]
    (is (= "Bearer sk-test" (get headers "Authorization")))))

(deftest anthropic-structure-test
  (testing "system message separated, correct headers"
    (let [{:keys [url headers body]} (p/build-request anthropic
                                                       {:messages [{:role :system :content "Be helpful"}
                                                                   {:role :user :content "hi"}]})]
      (is (= "https://api.anthropic.com/v1/messages" url))
      (is (= "sk-ant-test" (get headers "x-api-key")))
      (is (= "Be helpful" (:system body)))
      (is (= [{:role "user" :content "hi"}] (:messages body)))))

  (testing "tool use response parsed"
    (let [body {:content [{:type "tool_use" :id "t1" :name "search" :input {:q "test"}}]}]
      (is (= [{:id "t1" :function {:name "search" :arguments {:q "test"}}}]
             (:tool-calls (p/parse-response anthropic {} body)))))))
