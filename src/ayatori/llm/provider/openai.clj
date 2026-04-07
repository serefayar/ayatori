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
                 (assoc :temperature (:temperature params)))]
      {:url (str base-url "/v1/chat/completions")
       :headers {"Authorization" (str "Bearer " api-key)}
       :body body}))

  (parse-response [_ params body]
    (let [choice (first (:choices body))]
      (when-not choice
        (throw (ex-info "No choices in LLM response" {:body body})))
      (p/parse-choice choice params)))

  (build-stream-request [_ messages tools]
    {:url (str base-url "/v1/chat/completions")
     :headers {"Authorization" (str "Bearer " api-key)}
     :body (cond-> {:model model
                    :messages (p/messages->wire messages)
                    :stream true}
             tools (assoc :tools (p/tools->wire tools)))})

  (parse-stream-chunk [_ chunk]
    (get-in chunk [:choices 0 :delta :content])))

(defn make-openai-provider
  "Creates an OpenAIProvider from config map."
  [{:keys [model base-url api-key]}]
  (->OpenAIProvider model (or base-url "https://api.openai.com") api-key))
