(ns ayatori.graph.llm
  (:require
   [ayatori.llm.http :as http]
   [ayatori.llm.provider :as p]
   [ayatori.llm.provider.anthropic :as anthropic]
   [ayatori.llm.provider.ollama :as ollama]
   [ayatori.llm.provider.openai :as openai]
   [ayatori.memory :as mem]
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]))

(defn- make-provider
  "Creates provider record from client config map."
  [{:keys [provider] :as config}]
  (case provider
    :ollama (ollama/make-ollama-provider config)
    :openai (openai/make-openai-provider config)
    :anthropic (anthropic/make-anthropic-provider config)
    (throw (ex-info "Unknown LLM provider" {:provider provider}))))

(defn init-state
  "Initializes LLM node state with memory and tracking fields."
  [config]
  (let [memory (mem/make-memory (:memory config))]
    (when-let [p (:prompt config)]
      (async/<!! (mem/add-message memory {:role :system :content p})))
    {:memory memory
     :turn-count 0
     :phase :idle
     :tool-calls []
     :tool-results []}))

(defn- add-message
  "Async helper. Returns channel yielding state."
  [state msg]
  (async/go
    (async/<! (mem/add-message (:memory state) msg))
    state))

(defn- invoke-llm [provider params]
  (let [{:keys [url body headers]} (p/build-request provider params)]
    (async/go
      (let [response (async/<! (http/async-post url body headers))]
        (if (instance? Throwable response)
          response
          (p/parse-response provider params response))))))

(defn- invoke-llm-stream
  "Invokes LLM with streaming. Returns channel of events:
   {:type :delta :delta \"text\"}
   {:type :tool-call ...}
   {:type :done :message {...}}
   or Throwable on error."
  [provider params]
  (let [{:keys [url body headers]} (p/build-request provider (assoc params :stream true))
        parse-fn (if (instance? ayatori.llm.provider.anthropic.AnthropicProvider provider)
                   p/parse-anthropic-sse
                   p/parse-openai-sse)
        out-ch (async/chan 100)]
    (async/go
      (let [http-ch (http/async-post-stream url body headers parse-fn)]
        (loop [accumulated "" tool-calls []]
          (if-let [event (async/<! http-ch)]
            (cond
              (instance? Throwable event)
              (async/>! out-ch event)

              ;; Check finish-reason first (final chunk may have both delta and finish-reason)
              (:finish-reason event)
              (let [final-content (str accumulated (:delta event))]
                (if (seq tool-calls)
                  (do
                    (doseq [tc tool-calls]
                      (async/>! out-ch {:type :tool-call
                                        :id (:id tc)
                                        :name (get-in tc [:function :name])
                                        :args (get-in tc [:function :arguments])}))
                    (async/>! out-ch {:type :done
                                      :message {:role :assistant
                                                :tool-calls tool-calls}}))
                  (async/>! out-ch {:type :done
                                    :message {:role :assistant
                                              :content final-content}})))

              (seq (:delta event))
              (do
                (async/>! out-ch {:type :delta :delta (:delta event)})
                (recur (str accumulated (:delta event)) tool-calls))

              (:tool-calls event)
              (recur accumulated (into tool-calls (:tool-calls event)))

              :else
              (recur accumulated tool-calls))
            (async/close! out-ch)))))
    out-ch))

(defn- call-llm [config messages]
  (let [custom-invoke? (:invoke-fn config)
        invoke-fn (or custom-invoke? invoke-llm)
        client-or-provider (if custom-invoke?
                             (:client config)
                             (make-provider (:client config)))
        params (cond-> {:messages messages}
                 (seq (:tools config))
                 (assoc :tools (:tools config))

                 (:response-format config)
                 (assoc :response-format (:response-format config)))]
    (invoke-fn client-or-provider params)))

(defn coerce-tool-args
  "Coerces tool arguments according to tool schema using Malli string transformer."
  [tools tool-name args]
  (if-let [tool (some #(when (= (:name %) tool-name) %) tools)]
    (if-let [schema (:schema tool)]
      (m/decode schema args (mt/string-transformer))
      args)
    args))

(defn- next-tool-route [config state]
  (let [idx (count (:tool-results state))
        tool-call (nth (:tool-calls state) idx)
        tool-name (:name (:function tool-call))
        raw-args (:arguments (:function tool-call))
        args (coerce-tool-args (:tools config) tool-name raw-args)]
    {:route (keyword tool-name)
     :data args}))

(defn- check-max-turns! [config state]
  (let [max-turns (or (:max-turns config) 50)]
    (when (>= (:turn-count state) max-turns)
      (throw (ex-info "LLM max turns exceeded"
                      {:max-turns max-turns :turn-count (:turn-count state)})))))

(defn- validate-response [response-format response]
  (let [schema (:schema response-format)]
    (if (m/validate schema response)
      {:valid true}
      {:valid false :errors (me/humanize (m/explain schema response))})))

(defn- structured-response? [response]
  (nil? (:role response)))

(defn- call-llm-validated
  "Calls LLM and validates structured output against Malli schema.
   Retries with error feedback up to :max-retries times."
  [config state]
  (let [rf (:response-format config)
        max-retries (or (:max-retries rf) 0)]
    (async/go
      (loop [state state, attempt 0]
        (let [response (async/<! (call-llm config (mem/get-messages (:memory state))))]
          (if (instance? Throwable response)
            {:response response :state state}
            (if (or (:tool-calls response) (nil? (:schema rf)))
              {:response response :state state}
              (let [data (if (structured-response? response)
                           response
                           {:content (:content response)})
                    result (validate-response rf data)]
                (if (:valid result)
                  {:response response :state state}
                  (if (< attempt max-retries)
                    (let [error-msg (str "Response validation failed: "
                                         (pr-str (:errors result))
                                         ". Fix your response to match the schema.")
                          state (async/<! (add-message state (if (structured-response? response)
                                                               {:role :assistant :content (pr-str response)}
                                                               response)))
                          state (async/<! (add-message state {:role :user :content error-msg}))
                          state (update state :turn-count inc)]
                      (check-max-turns! config state)
                      (recur state (inc attempt)))
                    {:response response :state state}))))))))))

(defn- process-response
  "Async. Returns channel yielding {:result ... :state ...}."
  [config state response]
  (async/go
    (if (:tool-calls response)
      (let [state (async/<! (add-message state {:role :assistant :tool-calls (:tool-calls response)}))
            state (assoc state
                         :phase :collecting-tools
                         :tool-calls (:tool-calls response)
                         :tool-results [])]
        {:result (next-tool-route config state)
         :state state})
      (let [structured? (structured-response? response)
            state (if structured?
                    (update state :turn-count inc)
                    (-> (async/<! (add-message state response))
                        (update :turn-count inc)))
            data (if structured? response {:content (:content response)})]
        {:result {:route :done :data data}
         :state state}))))

(defn- handle-idle [config state input]
  (async/go
    (try
      (let [content (if (string? input) input (pr-str input))
            state (async/<! (add-message state {:role :user :content content}))
            state (update state :turn-count inc)]
        (check-max-turns! config state)
        (let [{:keys [response state]} (async/<! (call-llm-validated config state))]
          (if (instance? Throwable response)
            response
            (async/<! (process-response config state response)))))
      (catch Exception e e))))

(defn- handle-collecting-tools [config state input]
  (let [idx (count (:tool-results state))
        tool-call (nth (:tool-calls state) idx)
        content (if (string? input) input (json/generate-string input))
        state (update state :tool-results conj
                      {:tool-call-id (:id tool-call) :content content})]
    (if (< (count (:tool-results state)) (count (:tool-calls state)))
      (async/go {:result (next-tool-route config state)
                 :state state})
      (async/go
        (try
          (let [state (loop [s state
                             results (:tool-results state)]
                        (if (empty? results)
                          s
                          (let [{:keys [tool-call-id content]} (first results)
                                s (async/<! (add-message s {:role :tool
                                                            :tool-call-id tool-call-id
                                                            :content content}))]
                            (recur s (rest results)))))
                state (assoc state
                             :phase :idle
                             :tool-calls []
                             :tool-results [])]
            (check-max-turns! config state)
            (let [{:keys [response state]} (async/<! (call-llm-validated config state))]
              (if (instance? Throwable response)
                response
                (async/<! (process-response config state response)))))
          (catch Exception e e))))))

(defn invoke-llm-node
  "Invokes an LLM node. Manages conversation and tool routing. Returns a channel."
  [config input node-state]
  (let [state (or node-state (init-state config))]
    (case (:phase state)
      :idle             (handle-idle config state input)
      :collecting-tools (handle-collecting-tools config state input))))

(defn start-stream
  "Starts LLM streaming. Returns channel of events.
   Used by executor for streaming mode."
  [config input state]
  (let [provider (make-provider (:client config))
        content (if (string? input) input (pr-str input))
        _ (async/<!! (mem/add-message (:memory state) {:role :user :content content}))
        messages (mem/get-messages (:memory state))
        params (cond-> {:messages messages}
                 (seq (:tools config)) (assoc :tools (:tools config))
                 (:response-format config) (assoc :response-format (:response-format config)))]
    (invoke-llm-stream provider params)))

(defn continue-stream-after-tool
  "Continues streaming after tool result. Returns [new-state new-stream-ch].
   Adds tool result to memory and starts new stream."
  [config state tool-call-id tool-result]
  (let [provider (make-provider (:client config))
        content (if (string? tool-result) tool-result (json/generate-string tool-result))
        _ (async/<!! (mem/add-message (:memory state) {:role :tool
                                                       :tool-call-id tool-call-id
                                                       :content content}))
        messages (mem/get-messages (:memory state))
        params (cond-> {:messages messages}
                 (seq (:tools config)) (assoc :tools (:tools config))
                 (:response-format config) (assoc :response-format (:response-format config)))
        stream-ch (invoke-llm-stream provider params)]
    [(update state :turn-count (fnil inc 0)) stream-ch]))
