(ns ayatori.llm.provider
  "LLM provider protocol and shared helpers."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [malli.json-schema :as json-schema]))

(defprotocol ILLMProvider
  "Protocol for LLM provider implementations."
  (build-request [this params] "Builds provider-specific HTTP request from params.")
  (parse-response [this params body] "Parses provider response into normalized format."))

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
  (mapv (fn [{:keys [role content tool-calls tool-call-id]}]
          (cond-> {:role (name role)}
            content      (assoc :content content)
            tool-calls   (assoc :tool_calls (tool-calls->wire tool-calls))
            tool-call-id (assoc :tool_call_id tool-call-id)))
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
    :json-object {:type "json_object"}
    (throw (ex-info "Unknown response format type" {:type type}))))

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
  (let [{:keys [content tool_calls]} (:message choice)]
    (cond
      (seq tool_calls)
      (cond-> {:role :assistant
               :tool-calls (parse-tool-calls tool_calls)}
        content (assoc :content content))

      (:response-format params)
      (json/parse-string content true)

      :else
      {:role :assistant :content content})))

(defn parse-openai-response
  "Parses OpenAI-compatible response into normalized format."
  [params body]
  (let [choice (first (:choices body))]
    (when-not choice
      (throw (ex-info "No choices in LLM response" {:body body})))
    (parse-choice choice params)))

;; SSE Streaming Parsers

(defn parse-openai-sse
  "Parses OpenAI/Ollama SSE line. Returns event map or nil."
  [line]
  (when (and (str/starts-with? line "data: ")
             (not= (subs line 6) "[DONE]"))
    (try
      (let [json-data (json/parse-string (subs line 6) true)
            {:keys [content tool_calls]} (get-in json-data [:choices 0 :delta])
            finish-reason (get-in json-data [:choices 0 :finish_reason])]
        (cond-> {}
          content       (assoc :delta content)
          tool_calls    (assoc :tool-calls (parse-tool-calls tool_calls))
          finish-reason (assoc :finish-reason finish-reason)))
      (catch Exception _ nil))))

(defn parse-anthropic-sse
  "Parses Anthropic SSE line. Returns event map or nil."
  [line]
  (when (str/starts-with? line "data: ")
    (try
      (let [json-data (json/parse-string (subs line 6) true)]
        (case (:type json-data)
          "content_block_delta"
          (when-let [text (get-in json-data [:delta :text])]
            {:delta text})

          "message_delta"
          (when-let [stop-reason (get-in json-data [:delta :stop_reason])]
            {:finish-reason stop-reason})

          "message_stop"
          {:finish-reason "stop"}

          nil))
      (catch Exception _ nil))))
