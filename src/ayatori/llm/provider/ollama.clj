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
                 (assoc :temperature (:temperature params))

                 (:stream params)
                 (assoc :stream true))]
      {:url (str base-url "/v1/chat/completions")
       :body body}))

  (parse-response [_ params body]
    (p/parse-openai-response params body)))

(defn make-ollama-provider
  "Creates an OllamaProvider from config map."
  [{:keys [model base-url]}]
  (->OllamaProvider model (or base-url "http://localhost:11434")))
