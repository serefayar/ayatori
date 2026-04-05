(ns ayatori.graph.llm
  (:require
   [ayatori.llm.http :as http]
   [ayatori.llm.provider :as provider]
   [ayatori.memory :as mem]
   [clojure.core.async :as async]
   [cheshire.core :as json]
   [malli.core :as m]
   [malli.error :as me]))

(defn- init-state [config]
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

(defn- invoke-llm [client params]
  (let [{:keys [url body]} (provider/build-request client params)]
    (async/go
      (let [response (async/<! (http/async-post url body))]
        (if (instance? Throwable response)
          response
          (provider/parse-response client params response))))))

(defn- call-llm [config messages]
  (let [invoke-fn (or (:invoke-fn config) invoke-llm)
        params (cond-> {:messages messages}
                 (seq (:tools config))
                 (assoc :tools (:tools config))

                 (:response-format config)
                 (assoc :response-format (:response-format config)))]
    (invoke-fn (:client config) params)))

(defn- next-tool-route [state]
  (let [idx (count (:tool-results state))
        tool-call (nth (:tool-calls state) idx)]
    {:route (keyword (:name (:function tool-call)))
     :data  (:arguments (:function tool-call))}))

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
  [_config state response]
  (async/go
    (if (:tool-calls response)
      (let [state (async/<! (add-message state {:role :assistant :tool-calls (:tool-calls response)}))
            state (assoc state
                         :phase :collecting-tools
                         :tool-calls (:tool-calls response)
                         :tool-results [])]
        {:result (next-tool-route state)
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
      (async/go {:result (next-tool-route state)
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

(defn- invoke-streaming [config input node-state]
  (let [state (or node-state (init-state config))
        content (if (string? input) input (pr-str input))
        state (async/<!! (add-message state {:role :user :content content}))
        state (update state :turn-count inc)
        {:keys [url body]} (provider/build-stream-request (:client config) (mem/get-messages (:memory state)) (:tools config))
        raw-ch (http/async-post-stream url body)
        out-ch (async/chan 32)]
    (async/go-loop []
      (if-let [chunk (async/<! raw-ch)]
        (do
          (when-not (instance? Throwable chunk)
            (when-let [content (provider/parse-stream-chunk (:client config) chunk)]
              (async/>! out-ch content)))
          (recur))
        (async/close! out-ch)))
    {:result out-ch :state state :streaming true}))

(defn invoke-llm-node
  "Invokes an LLM node. Manages conversation and tool routing. Returns a channel."
  [config input node-state]
  (if (:stream config)
    (async/go (invoke-streaming config input node-state))
    (let [state (or node-state (init-state config))]
      (case (:phase state)
        :idle             (handle-idle config state input)
        :collecting-tools (handle-collecting-tools config state input)))))
