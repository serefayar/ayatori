(ns ayatori.llm.provider-test
  (:require
   [ayatori.llm.provider :as provider]
   [clojure.test :refer [deftest is testing]]))

(def ollama-client
  {:provider :ollama
   :model    "gpt-oss:20b"
   :base-url "http://localhost:11434"})

(deftest build-request-plain-chat-test
  (testing "plain chat builds correct request"
    (let [params {:messages [{:role :user :content "hello"}]}
          {:keys [url body]} (provider/build-request ollama-client params)]
      (is (= "http://localhost:11434/v1/chat/completions" url))
      (is (= "gpt-oss:20b" (:model body)))
      (is (= [{:role "user" :content "hello"}] (:messages body)))
      (is (nil? (:tools body)))
      (is (nil? (:response_format body))))))

(deftest build-request-with-tools-test
  (testing "tools are converted to wire format"
    (let [params {:messages [{:role :user :content "weather?"}]
                  :tools    [{:name        "get_weather"
                              :description "Get weather"
                              :schema      [:map [:city :string]]}]}
          {:keys [body]} (provider/build-request ollama-client params)]
      (is (= 1 (count (:tools body))))
      (is (= "get_weather" (get-in body [:tools 0 :function :name]))))))

(deftest build-request-with-response-format-test
  (testing "response-format is converted to wire format"
    (let [params {:messages        [{:role :user :content "extract"}]
                  :response-format {:type   :json-schema
                                    :schema [:map [:name :string]]}}
          {:keys [body]} (provider/build-request ollama-client params)]
      (is (= "json_schema" (get-in body [:response_format :type])))
      (is (= true (get-in body [:response_format :json_schema :strict])))
      (is (= {:type "object"
              :properties {:name {:type "string"}}
              :required [:name]}
             (get-in body [:response_format :json_schema :schema]))))))

(deftest parse-response-structured-output-test
  (testing "structured output response is parsed as JSON map"
    (let [params {:messages        [{:role :user :content "extract"}]
                  :response-format {:type :json-schema}}
          body {:choices [{:message {:role    "assistant"
                                     :content "{\"name\":\"Alice\",\"total\":99.5}"}}]}]
      (is (= {:name "Alice" :total 99.5}
             (provider/parse-response ollama-client params body))))))
