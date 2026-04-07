(ns ayatori.llm.provider
  "LLM provider protocol and shared helpers."
  (:require
   [cheshire.core :as json]
   [malli.json-schema :as json-schema]))

(defprotocol ILLMProvider
  "Protocol for LLM provider implementations."
  (build-request [this params] "Builds provider-specific HTTP request from params.")
  (parse-response [this params body] "Parses provider response into normalized format.")
  (build-stream-request [this messages tools] "Builds provider-specific streaming request.")
  (parse-stream-chunk [this chunk] "Extracts content delta from streaming chunk."))

(defn tool-calls->wire
  "Converts internal tool-calls to OpenAI wire format."
  [tool-calls]
  (mapv (fn [tc]
          {:id (:id tc)
           :type "function"
           :function {:name (name (get-in tc [:function :name]))
                      :arguments (let [args (get-in tc [:function :arguments])]
                                   (if (string? args) args (json/generate-string args)))}})
        tool-calls))

(defn messages->wire
  "Converts internal messages to OpenAI wire format."
  [messages]
  (mapv (fn [m]
          (cond-> {:role (name (:role m))}
            (contains? m :content)    (assoc :content (:content m))
            (:tool-calls m)           (assoc :tool_calls (tool-calls->wire (:tool-calls m)))
            (:tool-call-id m)         (assoc :tool_call_id (:tool-call-id m))))
        messages))

(defn tools->wire
  "Converts internal tools to OpenAI wire format."
  [tools]
  (mapv (fn [t]
          {:type "function"
           :function {:name (:name t)
                      :description (:description t)
                      :parameters (json-schema/transform (:schema t))}})
        tools))

(defn response-format->wire
  "Converts response format to OpenAI wire format."
  [{:keys [type schema]}]
  (case type
    :json-schema {:type "json_schema"
                  :json_schema {:name "response"
                                :strict true
                                :schema (json-schema/transform schema)}}
    :json-object {:type "json_object"}))

(defn parse-tool-calls
  "Parses tool_calls from OpenAI wire format."
  [tool-calls]
  (mapv (fn [tc]
          {:id (:id tc)
           :function {:name (get-in tc [:function :name])
                      :arguments (let [args (get-in tc [:function :arguments])]
                                   (if (string? args)
                                     (json/parse-string args true)
                                     args))}})
        tool-calls))

(defn parse-choice
  "Parses a single choice from OpenAI-compatible response."
  [choice params]
  (let [message    (:message choice)
        tool-calls (:tool_calls message)]
    (cond
      (seq tool-calls)
      (cond-> {:role :assistant
               :tool-calls (parse-tool-calls tool-calls)}
        (contains? message :content) (assoc :content (:content message)))

      (:response-format params)
      (json/parse-string (:content message) true)

      :else
      {:role :assistant
       :content (:content message)})))
