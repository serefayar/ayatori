(ns ayatori.llm.provider
  (:require
   [cheshire.core :as json]
   [malli.json-schema :as json-schema]))

(defmulti build-request
  "Builds a provider-specific HTTP request body from client config and params."
  (fn [client _params] (:provider client)))

(defmulti parse-response
  "Parses provider-specific HTTP response into normalized format."
  (fn [client _params _body] (:provider client)))

(defn- tool-calls->wire [tool-calls]
  (mapv (fn [tc]
          {:id (:id tc)
           :type "function"
           :function {:name (name (get-in tc [:function :name]))
                      :arguments (let [args (get-in tc [:function :arguments])]
                                   (if (string? args) args (json/generate-string args)))}})
        tool-calls))

(defn- messages->wire [messages]
  (mapv (fn [m]
          (cond-> {:role (name (:role m))}
            (contains? m :content)    (assoc :content (:content m))
            (:tool-calls m)           (assoc :tool_calls (tool-calls->wire (:tool-calls m)))
            (:tool-call-id m)         (assoc :tool_call_id (:tool-call-id m))))
        messages))

(defn- tools->wire [tools]
  (mapv (fn [t]
          {:type "function"
           :function {:name (:name t)
                      :description (:description t)
                      :parameters (json-schema/transform (:schema t))}})
        tools))

(defn- response-format->wire [{:keys [type schema]}]
  (case type
    :json-schema {:type "json_schema"
                  :json_schema {:name "response"
                                :strict true
                                :schema (json-schema/transform schema)}}
    :json-object {:type "json_object"}))

(defmethod build-request :ollama
  [client params]
  (let [body (cond-> {:model (:model client)
                      :messages (messages->wire (:messages params))}
               (:tools params)
               (assoc :tools (tools->wire (:tools params)))

               (:response-format params)
               (assoc :response_format (response-format->wire (:response-format params)))

               (:temperature params)
               (assoc :temperature (:temperature params)))]
    {:url (str (:base-url client) "/v1/chat/completions")
     :body body}))

(defmulti build-stream-request
  "Builds provider-specific streaming request."
  (fn [client _messages _tools] (:provider client)))

(defmethod build-stream-request :ollama
  [client messages tools]
  {:url (str (:base-url client) "/v1/chat/completions")
   :body (cond-> {:model (:model client)
                  :messages (messages->wire messages)
                  :stream true}
           tools (assoc :tools (tools->wire tools)))})

(defn- parse-tool-calls [tool-calls]
  (mapv (fn [tc]
          {:id (:id tc)
           :function {:name (get-in tc [:function :name])
                      :arguments (let [args (get-in tc [:function :arguments])]
                                   (if (string? args)
                                     (json/parse-string args true)
                                     args))}})
        tool-calls))

(defn- parse-choice [choice params]
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

(defmethod parse-response :ollama
  [_client params body]
  (let [choice (first (:choices body))]
    (when-not choice
      (throw (ex-info "No choices in LLM response" {:body body})))
    (parse-choice choice params)))

(defmulti parse-stream-chunk
  "Extracts content delta from a streaming chunk. Returns nil if no content."
  (fn [client _chunk] (:provider client)))

(defmethod parse-stream-chunk :ollama
  [_client chunk]
  (get-in chunk [:choices 0 :delta :content]))
