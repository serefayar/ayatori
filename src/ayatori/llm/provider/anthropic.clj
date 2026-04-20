(ns ayatori.llm.provider.anthropic
  "Anthropic Claude LLM provider implementation."
  (:require
   [ayatori.llm.provider :as p]
   [cheshire.core :as json]
   [clojure.string :as str]
   [malli.json-schema :as json-schema]))

(defn- extract-system-message
  "Separates system message from the message list.
   Returns {:system text-or-nil :other messages}."
  [messages]
  (let [{system true other false} (group-by #(= :system (:role %)) messages)]
    {:system (when (seq system) (str/join "\n" (map :content system)))
     :other (or other [])}))

(defn- tool-calls->anthropic-content
  "Converts internal tool-calls to Anthropic content blocks."
  [tool-calls]
  (mapv (fn [tc]
          {:type "tool_use"
           :id (:id tc)
           :name (name (get-in tc [:function :name]))
           :input (let [args (get-in tc [:function :arguments])]
                    (if (string? args) (json/parse-string args true) args))})
        tool-calls))

(defn- message->anthropic-wire
  "Converts a single message to Anthropic wire format."
  [m]
  (cond
    (:tool-calls m)
    {:role "assistant"
     :content (tool-calls->anthropic-content (:tool-calls m))}

    (:tool-call-id m)
    {:role "user"
     :content [{:type "tool_result"
                :tool_use_id (:tool-call-id m)
                :content (:content m)}]}

    :else
    {:role (name (:role m))
     :content (:content m)}))

(defn- messages->anthropic-wire
  "Converts internal messages to Anthropic wire format (excluding system)."
  [messages]
  (mapv message->anthropic-wire messages))

(defn- tools->anthropic-wire
  "Converts internal tools to Anthropic wire format."
  [tools]
  (mapv (fn [t]
          {:name (:name t)
           :description (:description t)
           :input_schema (json-schema/transform (:schema t))})
        tools))

(defn- parse-anthropic-tool-use
  "Parses tool_use blocks from Anthropic response."
  [content-blocks]
  (let [tool-uses (filter #(= "tool_use" (:type %)) content-blocks)]
    (mapv (fn [tu]
            {:id (:id tu)
             :function {:name (:name tu)
                        :arguments (:input tu)}})
          tool-uses)))

(defn- parse-anthropic-response
  "Parses Anthropic response into normalized format."
  [params body]
  (let [content (:content body)
        {:strs [text tool_use]} (group-by :type content)
        text-content (str/join (map :text text))]
    (cond
      (seq tool_use)
      (cond-> {:role :assistant
               :tool-calls (parse-anthropic-tool-use content)}
        (seq text-content) (assoc :content text-content))

      (:response-format params)
      (json/parse-string text-content true)

      :else
      {:role :assistant
       :content text-content})))

(defrecord AnthropicProvider [model base-url api-key]
  p/ILLMProvider
  (build-request [_ params]
    (let [{:keys [system other]} (extract-system-message (:messages params))
          body (cond-> {:model model
                        :messages (messages->anthropic-wire other)
                        :max_tokens 4096}
                 system
                 (assoc :system system)

                 (:tools params)
                 (assoc :tools (tools->anthropic-wire (:tools params)))

                 (:temperature params)
                 (assoc :temperature (:temperature params))

                 (:stream params)
                 (assoc :stream true))]
      {:url (str base-url "/v1/messages")
       :headers {"x-api-key" api-key
                 "anthropic-version" "2023-06-01"}
       :body body}))

  (parse-response [_ params body]
    (parse-anthropic-response params body)))

(defn make-anthropic-provider
  "Creates an AnthropicProvider from config map."
  [{:keys [model base-url api-key]}]
  (->AnthropicProvider model (or base-url "https://api.anthropic.com") api-key))
