(ns ayatori.llm.provider.openai
  "OpenAI LLM provider implementation."
  (:require
   [ayatori.llm.provider :as p]))

(defrecord OpenAIProvider [model base-url api-key]
  p/ILLMProvider
  (build-request [_ params]
    (let [body (cond-> {:model model
                        :messages (p/messages->wire (:messages params))}
                 (:tools params)
                 (assoc :tools (p/tools->wire (:tools params)))

                 (:response-format params)
                 (assoc :response_format (p/response-format->wire (:response-format params)))

                 (:temperature params)
                 (assoc :temperature (:temperature params))

                 (:stream params)
                 (assoc :stream true))]
      {:url (str base-url "/v1/chat/completions")
       :headers {"Authorization" (str "Bearer " api-key)}
       :body body}))

  (parse-response [_ params body]
    (p/parse-openai-response params body)))

(defn make-openai-provider
  "Creates an OpenAIProvider from config map."
  [{:keys [model base-url api-key]}]
  (->OpenAIProvider model (or base-url "https://api.openai.com") api-key))
