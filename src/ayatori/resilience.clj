(ns ayatori.resilience
  "Resilience patterns: timeout, retry, circuit-breaker, rate-limit, bulkhead, fallback."
  (:require
   [clojure.set :as set])
  (:import
   [dev.failsafe CircuitBreaker RateLimiter Bulkhead Failsafe Timeout RetryPolicy]
   [dev.failsafe.function CheckedSupplier]
   [java.time Duration]))

;; Registries for stateful resilience objects (shared across invocations)

(defonce ^:private circuit-breakers (atom {}))
(defonce ^:private rate-limiters (atom {}))
(defonce ^:private bulkheads (atom {}))

(defn- get-or-create [registry key create-fn]
  (or (get @registry key)
      (let [obj (create-fn)]
        (swap! registry assoc key obj)
        obj)))

(defn- make-circuit-breaker [{:keys [failure-threshold success-threshold delay-ms]}]
  (cond-> (CircuitBreaker/builder)
    failure-threshold (.withFailureThreshold failure-threshold)
    success-threshold (.withSuccessThreshold success-threshold)
    delay-ms (.withDelay (Duration/ofMillis delay-ms))
    true (.build)))

(defn- make-rate-limiter [{:keys [rate period]}]
  (let [period-ms (case (or period :second)
                    :second 1000
                    :minute 60000
                    :hour 3600000
                    1000)]
    (-> (RateLimiter/smoothBuilder rate (Duration/ofMillis period-ms))
        (.build))))

(defn- make-bulkhead [{:keys [concurrency]}]
  (-> (Bulkhead/builder concurrency)
      (.build)))

(defn- make-timeout [timeout-ms]
  (-> (Timeout/builder (Duration/ofMillis timeout-ms))
      (.withInterrupt)
      (.build)))

(defn- make-retry-policy [{:keys [max-retries backoff-ms retry-exceptions retry-callback]}]
  (let [builder (RetryPolicy/builder)]
    (.withMaxRetries builder (or max-retries 0))
    (when backoff-ms
      (let [[initial strategy & args] backoff-ms]
        (case strategy
          :constant (.withDelay builder (Duration/ofMillis initial))
          :exponential (let [[multiplier max-delay] args]
                         (.withBackoff builder
                                       (Duration/ofMillis initial)
                                       (Duration/ofMillis max-delay)
                                       multiplier))
          nil)))
    (when retry-exceptions
      (.handle builder ^"[Ljava.lang.Class;" (into-array Class retry-exceptions)))
    (when retry-callback
      (.onRetry builder
                (reify dev.failsafe.event.EventListener
                  (accept [_ event]
                    (retry-callback (.getLastException ^dev.failsafe.event.ExecutionAttemptedEvent event))))))
    (.build builder)))

;; Main wrapper

(defn wrap-with-resilience
  "Wraps function with resilience patterns.
   Execution order: rate-limit -> bulkhead -> circuit-breaker -> timeout -> retry (with fallback)
   Registry key is [agent-key node-key] to isolate state per agent."
  [f {:keys [timeout-ms retry circuit-breaker rate-limit bulkhead fallback]} agent-key node-key]
  (let [registry-key [agent-key node-key]
        cb (when circuit-breaker
             (get-or-create circuit-breakers registry-key #(make-circuit-breaker circuit-breaker)))
        rl (when rate-limit
             (get-or-create rate-limiters registry-key #(make-rate-limiter rate-limit)))
        bh (when bulkhead
             (get-or-create bulkheads registry-key #(make-bulkhead bulkhead)))
        timeout (when timeout-ms (make-timeout timeout-ms))
        retry-policy (when (and retry (pos? (or (:max-retries retry) 0)))
                       (make-retry-policy retry))
        policies (cond-> []
                   cb (conj cb)
                   timeout (conj timeout)
                   retry-policy (conj retry-policy))]
    (fn [input]
      (let [inner-execute (fn []
                            (when rl (.acquirePermit ^RateLimiter rl))
                            (if bh
                              (do
                                (.acquirePermit ^Bulkhead bh)
                                (try
                                  (f input)
                                  (finally
                                    (.releasePermit ^Bulkhead bh))))
                              (f input)))
            execute-fn (reify CheckedSupplier
                         (get [_] (inner-execute)))
            wrap-result (fn [result]
                          (if (and (map? result) (contains? result :result))
                            result
                            {:result result}))]
        (try
          (if (seq policies)
            (wrap-result (-> (Failsafe/with ^java.util.List policies)
                             (.get execute-fn)))
            (wrap-result (inner-execute)))
          (catch dev.failsafe.TimeoutExceededException _
            (throw (ex-info "Resilience failure"
                            {:ayatori/error-type :timeout
                             :agent agent-key
                             :node node-key
                             :pattern :timeout
                             :config {:timeout-ms timeout-ms}})))
          (catch dev.failsafe.CircuitBreakerOpenException _
            (throw (ex-info "Resilience failure"
                            {:ayatori/error-type :circuit-open
                             :agent agent-key
                             :node node-key
                             :pattern :circuit-breaker
                             :config circuit-breaker})))
          (catch Exception e
            (if fallback
              {:result (fallback input e)}
              (throw e))))))))

;; Config resolution

(defn resolve-node-config
  "Resolves resilience config for a node.
   Merge order: defaults < node specific."
  [resilience-config agent-key node-key]
  (when resilience-config
    (let [defaults (:defaults resilience-config)
          specific (get-in resilience-config [agent-key node-key])
          merged (merge defaults specific)]
      (when (seq merged)
        merged))))

;; Node type validation

(def ^:private allowed-patterns
  {:pure #{:timeout-ms :retry :circuit-breaker :rate-limit :bulkhead :fallback}
   :router #{}
   :llm #{:timeout-ms :retry :circuit-breaker :rate-limit :bulkhead :fallback}
   :fan-out #{}
   :dep #{:timeout-ms :retry :circuit-breaker :rate-limit :bulkhead :fallback}})

(defn allowed-patterns-for-type
  "Returns set of allowed resilience patterns for a node type."
  [node-type]
  (get allowed-patterns node-type #{}))

(defn validate-config
  "Validates resilience config against node type. Returns {:valid? bool :invalid-keys [...]}."
  [config node-type]
  (let [allowed (allowed-patterns-for-type node-type)
        configured (set (keys config))
        invalid (set/difference configured allowed)]
    {:valid? (empty? invalid)
     :invalid-keys (vec invalid)}))

;; Topology injection

(defn- make-wrapper-node-key
  "Generates wrapper node key. E.g., :ayatori.res/timeout:llm"
  [target-key patterns]
  (let [pattern-str (clojure.string/join "+" (map name (sort patterns)))]
    (keyword "ayatori.res" (str pattern-str ":" (name target-key)))))

(defn- config->patterns
  "Extracts active pattern names from config."
  [config]
  (cond-> #{}
    (:timeout-ms config) (conj :timeout)
    (:retry config) (conj :retry)
    (:circuit-breaker config) (conj :circuit-breaker)
    (:rate-limit config) (conj :rate-limit)
    (:bulkhead config) (conj :bulkhead)
    (:fallback config) (conj :fallback)))

(defn- make-wrapper-proc-spec
  "Creates proc spec for wrapper node."
  [target-key config]
  (let [patterns (config->patterns config)]
    {:ins {:in "input"}
     :outs {:out "output"}
     :workload :io
     :wrapper {:target target-key
               :patterns patterns
               :config config}}))

(defn- rewire-incoming-connections
  "Rewires connections targeting target-key to point to wrapper-key."
  [conns target-key wrapper-key]
  (mapv (fn [[from [to-node to-port :as to]]]
          (if (= to-node target-key)
            [from [wrapper-key to-port]]
            [from to]))
        conns))

(defn- add-wrapper-connection
  "Adds connection from wrapper to original target."
  [conns wrapper-key target-key]
  (conj conns [[wrapper-key :out] [target-key :in]]))

(defn inject-resilience-topology
  "Injects wrapper nodes into topology based on resilience config.
   Returns updated topology with wrapper procs and rewired conns."
  [topology agent-resilience-config]
  (if (empty? agent-resilience-config)
    topology
    (reduce-kv
     (fn [topo node-key node-config]
       (let [patterns (config->patterns node-config)]
         (if (empty? patterns)
           topo
           (let [wrapper-key (make-wrapper-node-key node-key patterns)
                 wrapper-spec (make-wrapper-proc-spec node-key node-config)]
             (-> topo
                 (assoc-in [:procs wrapper-key] wrapper-spec)
                 (update :conns rewire-incoming-connections node-key wrapper-key)
                 (update :conns add-wrapper-connection wrapper-key node-key)
                 (update-in [:node-types :wrapper] (fnil conj #{}) wrapper-key))))))
     topology
     agent-resilience-config)))

;; Reset functions (for testing)

(defn reset-circuit-breakers! []
  (reset! circuit-breakers {}))

(defn reset-rate-limiters! []
  (reset! rate-limiters {}))

(defn reset-bulkheads! []
  (reset! bulkheads {}))

(defn reset-all! []
  (reset-circuit-breakers!)
  (reset-rate-limiters!)
  (reset-bulkheads!))
