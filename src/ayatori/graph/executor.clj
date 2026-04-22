(ns ayatori.graph.executor
  "Graph executor using core.async.flow."
  (:require
   [ayatori.graph.llm :as llm]
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]))

;; Node type predicates

(defn- fan-out-node? [node]
  (= :fan-out (:type node)))

(defn- llm-node? [node]
  (= :llm (:type node)))

;; Var/fn helpers

(defn- fn-or-var?
  "Returns true if x is a function or a var pointing to a function."
  [x]
  (or (fn? x)
      (and (var? x) (fn? @x))))

(defn- ->fn
  "Returns a function that derefs var at call time, or the fn itself."
  [node-fn]
  (if (var? node-fn)
    (fn [data] (@node-fn data))
    node-fn))

;; Port map helpers

(defn- ->port-map
  "Creates port map from keys. {:a \"\" :b \"\"}."
  [ks]
  (zipmap ks (repeat "")))

(defn- ->result-ports
  "Creates result port map for fan-out branches. {:result-a \"\" :result-b \"\"}."
  [branches]
  (->port-map (map #(keyword (str "result-" (name %))) branches)))

;; Step function builders

(defn- pure-node->step
  "Wraps a pure node fn as a Flow step using map->step.
   Supports vars for REPL reloadability."
  [node-fn]
  (let [f (->fn node-fn)]
    (flow/map->step
     {:describe (fn [] {:ins {:in "node input"}
                        :outs {:out "node output"}})
      :transform
      (fn [state _in-id msg]
        (let [{:keys [data corr-id fan-out-id]} msg
              result (try
                       (f data)
                       (catch Throwable e
                         (throw (ex-info "Node execution failed"
                                         {:corr-id corr-id} e))))
              output (cond
                       (nil? result) data
                       (not (map? result)) result
                       :else (get result :result data))]
          [state {:out [(cond-> {:data output :corr-id corr-id}
                          fan-out-id (assoc :fan-out-id fan-out-id))]}]))})))

(defn- router-node->step
  "Wraps a router node (conditional edges) as a Flow step.
   Supports vars for REPL reloadability."
  [node-key node-fn edge-map]
  (let [f (->fn node-fn)
        out-ports (->port-map (keys edge-map))]
    (fn step
      ([] {:ins {:in "node input"} :outs out-ports})
      ([_params] {})
      ([state _transition] state)
      ([state in-id msg]
       (case in-id
         :in (let [{:keys [data corr-id]} msg
                   raw-result (try
                                (f data)
                                (catch Throwable e
                                  (throw (ex-info "Node execution failed"
                                                  {:corr-id corr-id :node node-key} e))))
                   result (if (and (map? raw-result) (contains? raw-result :result))
                            (:result raw-result)
                            raw-result)
                   route-key (:route result)
                   output-data (or (:data result) result)]
               (when-not route-key
                 (throw (ex-info "Missing :route" {:corr-id corr-id :node node-key})))
               (when-not (contains? out-ports route-key)
                 (throw (ex-info "Unknown route"
                                 {:corr-id corr-id :node node-key
                                  :route route-key :valid (keys out-ports)})))
               [state {route-key [{:data output-data :corr-id corr-id}]}]))))))

(defn- llm-node->step
  "Wraps an LLM node as a Flow step. Handles async invocation and routing.
   Uses flow step state to persist LLM conversation state across invocations.
   Routes not in edge-map go to ::terminal (implicit sink).
   Streaming uses self-message pattern: each token is a separate invocation."
  [node-key config edge-map]
  (let [valid-routes (conj (set (keys edge-map)) :done)
        streaming? (:stream config)
        out-ports (cond-> (assoc (->port-map (keys edge-map))
                                 ::terminal "final result")
                    streaming? (assoc ::self-out "stream loop"
                                      ::token-out "stream token"
                                      ::done-out "stream done"))]
    (fn step
      ([] {:ins (cond-> {:in "node input"}
                  streaming? (assoc ::self-in "stream loop"))
           :outs out-ports
           :workload :io})
      ([_params] (llm/init-state config))
      ([state _transition] state)
      ([state in-id msg]
       (case in-id
         :in
         (if streaming?
           ;; Streaming: check if this is tool result or new request
           (if (:pending-tool-call state)
             ;; Tool result arrived, continue streaming with new stream
             (let [tool-call-id (:pending-tool-call state)
                   [new-state stream-ch] (llm/continue-stream-after-tool
                                          config state tool-call-id (:data msg))]
               [(-> new-state
                    (dissoc :pending-tool-call)
                    (assoc :stream-ch stream-ch))
                {::self-out [{:type :next}]}])
             ;; New streaming request
             (let [{:keys [data corr-id]} msg
                   stream-ch (llm/start-stream config data state)]
               [(assoc state :stream-ch stream-ch :corr-id corr-id :accumulated "")
                {::self-out [{:type :next}]}]))
           ;; Non-streaming path
           (let [{:keys [data corr-id]} msg
                 result-ch (llm/invoke-llm-node config data state)
                 raw-result (async/<!! result-ch)]
             (when (instance? Throwable raw-result)
               (throw (ex-info "LLM invocation failed"
                               {:corr-id corr-id :node node-key} raw-result)))
             (let [{:keys [result]} raw-result
                   new-state (:state raw-result)
                   route-key (:route result)
                   output-data (or (:data result) result)]
               (when-not route-key
                 (throw (ex-info "Missing :route" {:corr-id corr-id :node node-key})))
               (when-not (contains? valid-routes route-key)
                 (throw (ex-info "Unknown route"
                                 {:corr-id corr-id :node node-key
                                  :route route-key :valid valid-routes})))
               (let [out-port (if (contains? edge-map route-key) route-key ::terminal)]
                 [new-state {out-port [{:data output-data :corr-id corr-id}]}]))))

         ::self-in
         ;; Streaming: read single event from stream channel
         (let [event (async/<!! (:stream-ch state))
               corr-id (:corr-id state)]
           (cond
             (nil? event)
             ;; Channel closed unexpectedly
             (throw (ex-info "Stream closed unexpectedly"
                             {:corr-id corr-id :node node-key}))

             (instance? Throwable event)
             (throw (ex-info "Stream error"
                             {:corr-id corr-id :node node-key} event))

             (= :done (:type event))
             (let [final-msg (:message event)]
               (if (:tool-calls final-msg)
                 ;; Done with tool calls: read next event (should be tool-call)
                 [state {::self-out [{:type :next}]}]
                 ;; Done with content: emit final result
                 (let [new-state (-> state
                                     (dissoc :stream-ch :corr-id :accumulated :pending-tool-call)
                                     (update :turn-count (fnil inc 0)))]
                   [new-state {::done-out [{:data final-msg :corr-id corr-id}]
                               ::terminal [{:data final-msg :corr-id corr-id}]}])))

             (= :delta (:type event))
             (let [new-state (update state :accumulated str (:delta event))]
               [new-state {::token-out [{:delta (:delta event) :corr-id corr-id}]
                           ::self-out [{:type :next}]}])

             (= :tool-call (:type event))
             (let [tool-name (keyword (:name event))
                   out-port (get edge-map tool-name)
                   coerced-args (llm/coerce-tool-args (:tools config) (:name event) (:args event))]
               (when-not out-port
                 (throw (ex-info "Unknown tool route"
                                 {:corr-id corr-id :node node-key :tool (:name event)})))
               [(assoc state :pending-tool-call (:id event))
                {out-port [{:data coerced-args :corr-id corr-id}]}])

             :else
             ;; Unknown event type, continue
             [state {::self-out [{:type :next}]}]))

         ;; Unknown port
         [state {}])))))

(defn- fan-out-node->step
  "Wraps a fan-out node as a Flow step.
   Broadcasts input to all branches, collects results, outputs to :out."
  [_node-key config]
  (let [branches (:branches config)
        branch-ins (->result-ports branches)
        branch-outs (->port-map branches)]
    (fn step
      ([] {:ins (assoc branch-ins :in "fan input")
           :outs (assoc branch-outs :out "collected")})
      ([_params] {:pending {}})
      ([state _transition] state)
      ([state in-id msg]
       (let [pending (:pending state)]
         (if (= in-id :in)
           (let [{:keys [data corr-id]} msg
                 fan-id (random-uuid)
                 outputs (into {} (map (fn [b]
                                         [b [{:data data
                                              :fan-out-id fan-id
                                              :branch b}]]))
                               branches)]
             [(assoc-in state [:pending fan-id] {:corr-id corr-id
                                                 :results {}
                                                 :expected (set branches)})
              outputs])
           (let [branch (keyword (second (re-matches #"result-(.+)" (name in-id))))
                 {:keys [data fan-out-id]} msg
                 pending-entry (get pending fan-out-id)]
             (if-not pending-entry
               [state {}]
               (let [updated-results (assoc (:results pending-entry) branch data)
                     all-done? (= (set (keys updated-results)) (:expected pending-entry))]
                 (if all-done?
                   [(assoc state :pending (dissoc pending fan-out-id))
                    {:out [{:data {:results updated-results}
                            :corr-id (:corr-id pending-entry)}]}]
                   [(assoc-in state [:pending fan-out-id :results] updated-results) {}]))))))))))

(defn- dep-node->step
  "Wraps a dep call as a Flow step. Uses :workload :io for blocking resolution."
  [dep-key resolve-fn]
  (fn step
    ([] {:ins {:in "dep input"}
         :outs {:out "dep result"}
         :workload :io})
    ([_params] {})
    ([state _transition] state)
    ([state in-id msg]
     (case in-id
       :in (let [{:keys [data corr-id fan-out-id]} msg
                 result (try
                          (resolve-fn data)
                          (catch Throwable e
                            (throw (ex-info "Dep resolution failed"
                                            {:corr-id corr-id :dep dep-key} e))))]
             [state {:out [(cond-> {:data result :corr-id corr-id}
                             fan-out-id (assoc :fan-out-id fan-out-id))]}])))))

;; Result delivery helpers

(defn- deliver-result!
  "Delivers result to caller via registry lookup. Removes entry after delivery."
  [registry corr-id result]
  (when-let [{:keys [ch]} (get @registry corr-id)]
    (async/put! ch result)
    (swap! registry dissoc corr-id)))

(defn- forward-token!
  "Forwards streaming token to caller's channel."
  [registry corr-id delta]
  (when-let [{:keys [ch]} (get @registry corr-id)]
    (async/put! ch {:type :token :delta delta})))

(defn- forward-result-and-close!
  "Forwards final result (without :type) and closes streaming channel."
  [registry corr-id data]
  (when-let [{:keys [ch]} (get @registry corr-id)]
    (async/put! ch data)
    (async/close! ch)
    (swap! registry dissoc corr-id)))

(defn- collector-ins
  "Returns output-collector ins based on whether streaming LLM nodes exist."
  [has-streaming?]
  (cond-> {:result "final result"}
    has-streaming? (assoc :token "stream token" :done "stream done")))

(defn- output-collector-step
  "Sink that delivers results/tokens/errors to callers via registry.
   Registry passed via closure, delivery is side-effect."
  [result-registry has-streaming?]
  (flow/map->step
   {:describe
    (fn [] {:ins (collector-ins has-streaming?)})

    :init
    (fn [_] {:registry result-registry})

    :transform
    (fn [state in-id msg]
      (let [registry (:registry state)]
        (case in-id
          :result (deliver-result! registry (:corr-id msg) (:data msg))
          :token  (forward-token! registry (:corr-id msg) (:delta msg))
          :done   (forward-result-and-close! registry (:corr-id msg) (:data msg))
          nil))
      [state {}])}))

;; Topology spec (static, for inspection)

(defn- classify-node
  "Returns the type of a node: :llm, :fan-out, :router, or :pure."
  [node edges]
  (cond
    (llm-node? node) :llm
    (fan-out-node? node) :fan-out
    (map? edges) :router
    :else :pure))

(defn- build-proc-spec
  "Builds a proc spec (ports and metadata) for a single node.
   Returns [type spec] tuple."
  [node edges]
  (let [node-type (classify-node node edges)]
    [node-type
     (case node-type
       :llm
       (let [streaming? (:stream node)]
         {:ins (cond-> {:in "node input"}
                 streaming? (assoc ::self-in "stream loop"))
          :outs (cond-> (assoc (->port-map (keys edges))
                               ::terminal "final result")
                  streaming? (assoc ::self-out "stream loop"
                                    ::token-out "stream token"
                                    ::done-out "stream done"))
          :workload :io})

       :fan-out
       (let [branches (:branches node)]
         {:ins (assoc (->result-ports branches) :in "fan input")
          :outs (assoc (->port-map branches) :out "collected")
          :branches branches})

       :router
       {:ins {:in "node input"}
        :outs (->port-map (keys edges))}

       :pure
       {:ins {:in "node input"}
        :outs {:out "node output"}})]))

(defn- analyze-nodes
  "Analyzes nodes and returns derived data structures used across topology building."
  [nodes edges]
  (let [fan-out-nodes (into {} (filter (comp fan-out-node? val)) nodes)
        branch-nodes (into #{} (mapcat :branches (vals fan-out-nodes)))
        streaming-llm-nodes (into #{} (keep (fn [[k v]]
                                              (when (and (llm-node? v) (:stream v)) k)))
                                  nodes)
        llm-dep-targets (reduce-kv
                         (fn [acc from to]
                           (if (and (llm-node? (get nodes from)) (map? to))
                             (reduce-kv (fn [m _ target] (assoc m target from)) acc to)
                             acc))
                         {}
                         edges)]
    {:fan-out-nodes fan-out-nodes
     :branch-nodes branch-nodes
     :streaming-llm-nodes streaming-llm-nodes
     :streaming? (seq streaming-llm-nodes)
     :llm-dep-targets llm-dep-targets}))

(defn- build-edge-connections
  "Builds connections from edges (regular and conditional)."
  [edges branch-nodes]
  (reduce-kv
   (fn [acc from to]
     (cond
       (contains? branch-nodes from) acc
       (keyword? to) (conj acc [[from :out] [to :in]])
       (map? to) (reduce-kv
                  (fn [cacc route-key target]
                    (if (= :ayatori/done target)
                      (conj cacc [[from route-key] [::output-collector :result]])
                      (conj cacc [[from route-key] [target :in]])))
                  acc to)
       :else acc))
   []
   edges))

(defn- build-fan-out-connections
  "Builds internal connections for fan-out nodes."
  [fan-out-nodes]
  (reduce-kv
   (fn [acc fan-key config]
     (reduce (fn [cacc branch]
               (-> cacc
                   (conj [[fan-key branch] [branch :in]])
                   (conj [[branch :out] [fan-key (keyword (str "result-" (name branch)))]])))
             acc
             (:branches config)))
   []
   fan-out-nodes))

(defn- build-streaming-connections
  "Builds self-loop and token/done connections for streaming LLM nodes."
  [streaming-llm-nodes]
  (into (mapv (fn [k] [[k ::self-out] [k ::self-in]]) streaming-llm-nodes)
        (mapcat (fn [k]
                  [[[k ::token-out] [::output-collector :token]]
                   [[k ::done-out] [::output-collector :done]]])
                streaming-llm-nodes)))

(defn- build-connections
  "Builds connection tuples from edges and fan-out configs."
  [edges {:keys [fan-out-nodes branch-nodes streaming-llm-nodes]}]
  (-> (build-edge-connections edges branch-nodes)
      (into (build-fan-out-connections fan-out-nodes))
      (into (build-streaming-connections streaming-llm-nodes))))

(defn- build-node-proc-specs
  "Builds proc-specs and node-types from nodes map."
  [nodes edges]
  (reduce-kv
   (fn [[procs types] k node]
     (if (or (fn-or-var? node) (llm-node? node) (fan-out-node? node))
       (let [[node-type spec] (build-proc-spec node (get edges k))]
         [(assoc procs k spec)
          (update types node-type (fnil conj #{}) k)])
       [procs types]))
   [{} {}]
   nodes))

(defn- add-dep-proc-specs
  "Adds proc-specs for deps."
  [[proc-specs node-types] deps]
  (reduce (fn [[procs types] dep-key]
            [(assoc procs dep-key {:ins {:in "dep input"}
                                   :outs {:out "dep result"}
                                   :workload :io})
             (update types :dep (fnil conj #{}) dep-key)])
          [proc-specs node-types]
          deps))

(defn- find-terminal-nodes
  "Finds nodes that have no outgoing edges (terminal nodes)."
  [nodes proc-specs edges deps branch-nodes]
  (let [edge-sources (set (keys edges))]
    (filter #(and (contains? proc-specs %)
                  (not (contains? edge-sources %))
                  (not (contains? branch-nodes %))
                  (not (contains? deps %)))
            (keys nodes))))

(defn- build-terminal-connections
  "Builds connections for terminal nodes to output-collector."
  [terminal-nodes nodes fan-out-nodes]
  (keep (fn [t]
          (when (or (fn-or-var? (get nodes t))
                    (contains? fan-out-nodes t))
            [[t :out] [::output-collector :result]]))
        terminal-nodes))

(defn- build-dep-connections
  "Builds connections for deps. Routes back to LLM if called from tool edge."
  [deps llm-dep-targets]
  (map (fn [d]
         (if-let [llm-node (get llm-dep-targets d)]
           [[d :out] [llm-node :in]]
           [[d :out] [::output-collector :result]]))
       deps))

(defn- build-llm-terminal-connections
  "Builds ::terminal -> output-collector connections for LLM nodes."
  [nodes]
  (keep (fn [[k v]]
          (when (llm-node? v)
            [[k ::terminal] [::output-collector :result]]))
        nodes))

(defn build-topology-spec
  "Builds static topology spec from agent. No flow objects, just data."
  [agent]
  (let [{:keys [nodes edges caps deps]} agent
        deps (set deps)

        {:keys [fan-out-nodes branch-nodes streaming? llm-dep-targets] :as analysis}
        (analyze-nodes nodes edges)

        [proc-specs node-types] (-> (build-node-proc-specs nodes edges)
                                    (add-dep-proc-specs deps))
        proc-specs (assoc proc-specs ::output-collector
                          {:ins (collector-ins streaming?) :outs {}})
        node-types (update node-types :collector (fnil conj #{}) ::output-collector)

        terminal-nodes (find-terminal-nodes nodes proc-specs edges deps branch-nodes)
        conns (-> (build-connections edges analysis)
                  (into (build-terminal-connections terminal-nodes nodes fan-out-nodes))
                  (into (build-dep-connections deps llm-dep-targets))
                  (into (build-llm-terminal-connections nodes)))]

    {:procs proc-specs
     :conns conns
     :entry-key (-> caps first val :entry)
     :deps deps
     :node-types node-types
     :streaming? streaming?}))

;; Topology building

(defn- build-flow-procs
  "Builds flow/process objects from agent. Reuses conns from topology."
  [agent dep-resolvers result-registry]
  (let [{:keys [nodes edges topology]} agent
        deps (:deps topology)
        router-nodes (into #{} (keep (fn [[k v]] (when (map? v) k))) edges)
        fan-out-nodes (into {} (filter (comp fan-out-node? val)) nodes)
        llm-nodes (into {} (filter (comp llm-node? val)) nodes)

        procs (reduce-kv
               (fn [acc k node]
                 (cond
                   (contains? fan-out-nodes k)
                   (assoc acc k {:proc (flow/process (fan-out-node->step k (get fan-out-nodes k)))})

                   (contains? llm-nodes k)
                   (assoc acc k {:proc (flow/process (llm-node->step k node (get edges k)))})

                   (not (fn-or-var? node))
                   acc

                   (contains? router-nodes k)
                   (assoc acc k {:proc (flow/process (router-node->step k node (get edges k)))})

                   :else
                   (assoc acc k {:proc (flow/process (pure-node->step node))})))
               {}
               nodes)

        procs (reduce (fn [acc dep-key]
                        (if-let [resolve-fn (get dep-resolvers dep-key)]
                          (assoc acc dep-key {:proc (flow/process (dep-node->step dep-key resolve-fn))})
                          acc))
                      procs
                      deps)

        procs (assoc procs ::output-collector
                     {:proc (flow/process (output-collector-step result-registry
                                                                 (:streaming? topology)))})]

    {:procs procs
     :conns (:conns topology)
     :entry-key (:entry-key topology)
     :deps deps}))

;; Flow lifecycle

(defn- make-dep-resolver
  "Creates a blocking (vthread) resolver function for a specific dep."
  [ref-resolver wiring agent-key dep-key]
  (fn [data]
    (let [target (get-in @wiring [agent-key dep-key])]
      (if target
        (async/<!! (ref-resolver target data))
        (throw (ex-info "Dep not wired" {:dep dep-key :agent agent-key}))))))

(defn- start-report-handler
  "Handles diagnostics from report channel. No application data."
  [report-chan]
  (async/go-loop []
    (when-let [_msg (async/<! report-chan)]
      (recur))))

(defn- start-error-handler
  "Handles flow-level errors. Extracts corr-id from exception and delivers to caller."
  [error-chan result-registry error-handlers]
  (async/go-loop []
    (when-let [err (async/<! error-chan)]
      (let [ex (::flow/ex err)
            corr-id (some-> ex ex-data :corr-id)]
        (when corr-id
          (deliver-result! result-registry corr-id ex))
        (doseq [handler @error-handlers]
          (handler ex)))
      (recur))))

(defn create-agent-flow
  "Creates and starts a flow for an agent. Returns flow state."
  [agent opts]
  (let [{:keys [topology]} agent
        ref-resolver (:ref-resolver opts)
        wiring (:wiring opts)
        agent-key (:agent opts)
        dep-resolvers (into {}
                            (map (fn [dep-key]
                                   [dep-key (make-dep-resolver ref-resolver wiring agent-key dep-key)]))
                            (:deps topology))
        result-registry (atom {})
        flow-topology (build-flow-procs agent dep-resolvers result-registry)
        flow-graph (flow/create-flow {:procs (:procs flow-topology)
                                      :conns (:conns flow-topology)})
        {:keys [report-chan error-chan]} (flow/start flow-graph)
        error-handlers (atom [])]

    (start-report-handler report-chan)
    (start-error-handler error-chan result-registry error-handlers)
    (flow/resume flow-graph)

    {:flow flow-graph
     :topology flow-topology
     :result-registry result-registry
     :error-handlers error-handlers}))

(defn stop-agent-flow
  "Stops an agent's flow."
  [flow-state]
  (when flow-state
    (flow/stop (:flow flow-state))))

(defn pause-flow
  "Pauses a flow graph."
  [flow-graph]
  (flow/pause flow-graph))

(defn resume-flow
  "Resumes a paused flow graph."
  [flow-graph]
  (flow/resume flow-graph))

(defn ping-flow
  "Pings a flow graph for health check. Returns channel."
  [flow-graph]
  (flow/ping flow-graph))

(defn inject
  "Injects input into an agent's flow. Returns promise channel for result."
  ([flow-state entry-key input]
   (inject flow-state entry-key input false))
  ([flow-state entry-key input streaming?]
   (let [corr-id (str (random-uuid))
         result-ch (if streaming?
                     (async/chan 100)
                     (async/promise-chan))]
     (swap! (:result-registry flow-state) assoc corr-id {:ch result-ch})
     (flow/inject (:flow flow-state)
                  [entry-key :in]
                  [{:data input
                    :corr-id corr-id}])
     result-ch)))

;; Capability check

(defn supports-graph?
  "Returns true if the graph can be executed with Flow."
  [bound-graph]
  (every? (fn [[_ v]] (or (fn-or-var? v) (fan-out-node? v) (llm-node? v)))
          (:nodes bound-graph)))
