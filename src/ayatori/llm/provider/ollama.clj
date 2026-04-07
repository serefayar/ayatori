    (ns ayatori.llm.provider.ollama
  "Ollama LLM provider implementation."
  (:require
   [ayatori.llm.provider :as p]))

(defrecord OllamaProvider [model base-url]
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
       :body body}))

  (parse-response [_ params body]
    (let [choice (first (:choices body))]
      (when-not choice
        (throw (ex-info "No choices in LLM response" {:body body})))
      (p/parse-choice choice params)))

  (build-stream-request [_ messages tools]
    {:url (str base-url "/v1/chat/completions")
     :body (cond-> {:model model
                    :messages (p/messages->wire messages)
                    :stream true}
             tools (assoc :tools (p/tools->wire tools)))})

  (parse-stream-chunk [_ chunk]
    (get-in chunk [:choices 0 :delta :content])))

(defn make-ollama-provider
  "Creates an OllamaProvider from config map."
  [{:keys [model base-url]}]
  (->OllamaProvider model (or base-url "http://localhost:11434")))
