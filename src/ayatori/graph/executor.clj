(ns ayatori.graph.executor
  "Graph executor using core.async.flow."
  (:require
   [ayatori.graph.llm :as llm]
   [ayatori.resilience :as resilience]
   [clojure.core.async :as async]
   [clojure.core.async.flow :as flow]))

;; Node type predicates

(defn- fan-out-node? [node]
  (= :fan-out (:type node)))

(defn- llm-node? [node]
  (= :llm (:type node)))

;; Var/fn helpers

(defn- fn-or-var?
  "Returns true if x is a function, var, or map with :fn key."
  [x]
  (or (fn? x)
      (and (var? x) (fn? @x))
      (and (map? x) (contains? x :fn) (not (contains? x :type)))))

(defn- ->fn
  "Returns a function that derefs var at call time, or the fn itself."
  [node]
  (let [f (if (and (map? node) (contains? node :fn)) (:fn node) node)]
    (if (var? f)
      (fn [data] (@f data))
      f)))

;; Port map helpers

(defn- ->port-map
  "Creates port map from keys. {:a \"\" :b \"\"}."
  [ks]
  (zipmap ks (repeat "")))

(defn- ->result-ports
  "Creates result port map for fan-out branches. {:result-a \"\" :result-b \"\"}."
  [branches]
  (->port-map (map #(keyword (str "result-" (name %))) branches)))

;; Edge helpers

(defn- route-target
  "Extracts target from a route (keyword or vector)."
  [route]
  (if (keyword? route)
    route
    (case (count route)
      1 (first route)   ;; [:target]
      2 (if (fn? (second route))
          (first route)   ;; [:target pred]
          (second route)) ;; [:label :target]
      (first route))))    ;; fallback

(defn- normalize-edge
  "Normalizes edge to vector of routes."
  [edge]
  (cond
    ;; :target → [[:default :target]]
    (keyword? edge)
    [[:default edge]]

    ;; vector of routes (may contain bare keywords)
    (and (vector? edge) (seq edge))
    (mapv (fn [r] (if (keyword? r) [:default r] r)) edge)

    :else
    (throw (ex-info "Invalid edge format" {:edge edge}))))

(defn- parse-route
  "Parses a route. Returns {:label :pred :target}."
  [route]
  (case (count route)
    1 (let [[target] route]
        {:label target :pred (constantly true) :target target})
    2 (if (fn? (second route))
        (let [[target pred] route]
          {:label target :pred pred :target target})
        (let [[label target] route]
          {:label label :pred (constantly true) :target target}))
    (throw (ex-info "Invalid route format" {:route route}))))

(defn- edge-targets
  "Extracts target nodes from routes."
  [edge]
  (mapv route-target (normalize-edge edge)))

(defn- has-conditional-routes?
  "Returns true if edge has routes with predicates."
  [edge]
  (and (vector? edge)
       (some #(and (vector? %) (= 2 (count %)) (fn? (second %))) edge)))

;; Step function builders

(defn- pure-node->step
  "Wraps a pure node fn as a Flow step using map->step.
   Supports vars for REPL reloadability. Applies resilience if configured."
  [node-fn node-key res-config agent-key]
  (let [f (->fn node-fn)
        wrapped-f (if res-config
                    (resilience/wrap-with-resilience f res-config agent-key node-key)
                    f)]
    (flow/map->step
     {:describe (fn [] {:ins {:in "node input"}
                        :outs {:out "node output"}})
      :transform
      (fn [state _in-id msg]
        (let [{:keys [data corr-id fan-out-id]} msg
              result (try
                       (wrapped-f data)
                       (catch Throwable e
                         (throw (ex-info "Node execution failed"
                                         {:corr-id corr-id :node node-key} e))))
              output (cond
                       (nil? result) data
                       (not (map? result)) result
                       :else (get result :result data))]
          [state {:out [(cond-> {:data output :corr-id corr-id}
                          fan-out-id (assoc :fan-out-id fan-out-id))]}]))})))

(defn- router-node->step
  "Wraps a router node as a Flow step using dispatch predicates.
   Evaluates predicates in order, first match wins."
  [node-key node-fn edge]
  (let [f (->fn node-fn)
        routes (mapv parse-route (normalize-edge edge))
        out-ports (->port-map (mapv :target routes))]
    (fn step
      ([] {:ins {:in "node input"} :outs out-ports})
      ([_params] {})
      ([state _transition] state)
      ([state in-id msg]
       (case in-id
         :in (let [{:keys [data corr-id]} msg
                   result (try
                            (f data)
                            (catch Throwable e
                              (throw (ex-info "Node execution failed"
                                              {:corr-id corr-id :node node-key} e))))
                   output-data (cond
                                 (nil? result) data
                                 (not (map? result)) result
                                 (contains? result :result) (:result result)
                                 :else result)
                   match (first (filter #((:pred %) output-data) routes))]
               (when-not match
                 (throw (ex-info "No matching dispatch route"
                                 {:corr-id corr-id :node node-key
                                  :routes (mapv :label routes)})))
               [state {(:target match) [{:data output-data
                                         :corr-id corr-id
                                         :route (:label match)}]}]))))))

(defn- llm-node->step
  "Wraps an LLM node as a Flow step. Handles async invocation and routing.
   Uses flow step state to persist LLM conversation state across invocations.
   Routes not in edge go to ::terminal (implicit sink).
   Streaming uses self-message pattern: each token is a separate invocation.
   Resilience applies to stream start and non-streaming invocations."
  [node-key config edge res-config agent-key]
  (let [routes (if edge (mapv parse-route (normalize-edge edge)) [])
        route-map (into {} (map (juxt :label :target)) routes)
        valid-routes (conj (set (keys route-map)) :done)
        streaming? (:stream config)
        out-ports (cond-> (assoc (->port-map (vals route-map))
                                 ::terminal "final result")
                    streaming? (assoc ::self-out "stream loop"
                                      ::token-out "stream token"
                                      ::done-out "stream done"))
        ;; Non-streaming invoke with resilience
        invoke-fn (when res-config
                    (resilience/wrap-with-resilience
                     (fn [args]
                       (let [[config* data state] args
                             result-ch (llm/invoke-llm-node config* data state)
                             raw-result (async/<!! result-ch)]
                         (when (instance? Throwable raw-result)
                           (throw raw-result))
                         raw-result))
                     res-config
                     agent-key
                     node-key))
        ;; Stream start with resilience (timeout, retry for connection)
        start-stream-fn (if res-config
                          (resilience/wrap-with-resilience
                           (fn [args]
                             (let [[config* data state] args]
                               {:result (llm/start-stream config* data state)}))
                           res-config
                           agent-key
                           node-key)
                          nil)
        continue-stream-fn (if res-config
                             (resilience/wrap-with-resilience
                              (fn [args]
                                (let [[config* state tool-call-id data] args]
                                  {:result (llm/continue-stream-after-tool config* state tool-call-id data)}))
                              res-config
                              agent-key
                              node-key)
                             nil)]
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
             ;; Tool result arrived, continue streaming with new stream (with resilience)
             (let [tool-call-id (:pending-tool-call state)
                   [new-state stream-ch] (if continue-stream-fn
                                           (:result (continue-stream-fn [config state tool-call-id (:data msg)]))
                                           (llm/continue-stream-after-tool config state tool-call-id (:data msg)))]
               [(-> new-state
                    (dissoc :pending-tool-call)
                    (assoc :stream-ch stream-ch))
                {::self-out [{:type :next}]}])
             ;; New streaming request (with resilience for stream start)
             (let [{:keys [data corr-id]} msg
                   stream-ch (if start-stream-fn
                               (:result (start-stream-fn [config data state]))
                               (llm/start-stream config data state))]
               [(assoc state :stream-ch stream-ch :corr-id corr-id :accumulated "")
                {::self-out [{:type :next}]}]))
           ;; Non-streaming path (with optional resilience)
           (let [{:keys [data corr-id]} msg
                 raw-result (if invoke-fn
                              (invoke-fn [config data state])
                              (let [result-ch (llm/invoke-llm-node config data state)
                                    r (async/<!! result-ch)]
                                (when (instance? Throwable r)
                                  (throw (ex-info "LLM invocation failed"
                                                  {:corr-id corr-id :node node-key} r)))
                                r))]
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
               (let [target (get route-map route-key)
                     out-port (if target target ::terminal)]
                 [new-state {out-port [{:data output-data :corr-id corr-id}]}]))))

         ::self-in
         ;; Streaming: read single event from stream channel
         (let [event (async/<!! (:stream-ch state))
               corr-id (:corr-id state)
               accumulated (:accumulated state "")]
           (cond
             (nil? event)
             ;; Channel closed unexpectedly, return partial response
             (let [partial-msg {:partial? true
                                :content accumulated
                                :error :stream-closed
                                :role :assistant}
                   new-state (-> state
                                 (dissoc :stream-ch :corr-id :accumulated :pending-tool-call)
                                 (update :turn-count (fnil inc 0)))]
               [new-state {::done-out [{:data partial-msg :corr-id corr-id}]
                           ::terminal [{:data partial-msg :corr-id corr-id}]}])

             (instance? Throwable event)
             ;; Stream error, return partial response with error info
             (let [partial-msg {:partial? true
                                :content accumulated
                                :error :stream-interrupted
                                :error-detail (ex-message event)
                                :role :assistant}
                   new-state (-> state
                                 (dissoc :stream-ch :corr-id :accumulated :pending-tool-call)
                                 (update :turn-count (fnil inc 0)))]
               [new-state {::done-out [{:data partial-msg :corr-id corr-id}]
                           ::terminal [{:data partial-msg :corr-id corr-id}]}])

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
                   target (get route-map tool-name)
                   coerced-args (llm/coerce-tool-args (:tools config) (:name event) (:args event))]
               (when-not target
                 (throw (ex-info "Unknown tool route"
                                 {:corr-id corr-id :node node-key :tool (:name event)})))
               [(assoc state :pending-tool-call (:id event))
                {target [{:data coerced-args :corr-id corr-id}]}])

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
  "Wraps a dep call as a Flow step. Uses :workload :io for blocking resolution.
   Applies resilience if configured."
  [dep-key resolve-fn res-config agent-key]
  (let [wrapped-fn (if res-config
                     (resilience/wrap-with-resilience resolve-fn res-config agent-key dep-key)
                     resolve-fn)]
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
                            (let [r (wrapped-fn data)]
                              (if (and (map? r) (contains? r :result))
                                (:result r)
                                r))
                            (catch Throwable e
                              (throw (ex-info "Dep resolution failed"
                                              {:corr-id corr-id :dep dep-key} e))))]
               [state {:out [(cond-> {:data result :corr-id corr-id}
                               fan-out-id (assoc :fan-out-id fan-out-id))]}]))))))

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
  [node edge]
  (cond
    (llm-node? node) :llm
    (fan-out-node? node) :fan-out
    (and edge (has-conditional-routes? edge)) :router
    :else :pure))

(defn- build-proc-spec
  "Builds a proc spec (ports and metadata) for a single node.
   Returns [type spec] tuple."
  [node edge]
  (let [node-type (classify-node node edge)]
    [node-type
     (case node-type
       :llm
       (let [streaming? (:stream node)
             targets (if edge (edge-targets edge) [])]
         {:ins (cond-> {:in "node input"}
                 streaming? (assoc ::self-in "stream loop"))
          :outs (cond-> (assoc (->port-map targets)
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
        :outs (->port-map (edge-targets edge))}

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
                         (fn [acc from edge]
                           (if (and (llm-node? (get nodes from)) edge)
                             (let [routes (normalize-edge edge)
                                   route-map (into {} (map (fn [r] [(if (= 1 (count r))
                                                                       :default
                                                                       (first r))
                                                                     (route-target r)]))
                                                   routes)]
                               (reduce-kv (fn [m label target]
                                            (if (= :done label)
                                              m
                                              (assoc m target from)))
                                          acc route-map))
                             acc))
                         {}
                         edges)]
    {:fan-out-nodes fan-out-nodes
     :branch-nodes branch-nodes
     :streaming-llm-nodes streaming-llm-nodes
     :streaming? (seq streaming-llm-nodes)
     :llm-dep-targets llm-dep-targets}))

(defn- build-edge-connections
  "Builds connections from edges. All edges use unified vector format."
  [edges branch-nodes nodes]
  (reduce-kv
   (fn [acc from edge]
     (if (contains? branch-nodes from)
       acc
       (let [routes (normalize-edge edge)
             node (get nodes from)
             is-llm? (llm-node? node)
             has-predicates? (has-conditional-routes? edge)]
         (reduce (fn [cacc route]
                   (let [target (route-target route)
                         out-port (cond
                                    is-llm? target
                                    has-predicates? target
                                    :else :out)]
                     (if (= :ayatori/done target)
                       (conj cacc [[from target] [::output-collector :result]])
                       (conj cacc [[from out-port] [target :in]]))))
                 acc
                 routes))))
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
  [nodes edges {:keys [fan-out-nodes branch-nodes streaming-llm-nodes]}]
  (-> (build-edge-connections edges branch-nodes nodes)
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
        conns (-> (build-connections nodes edges analysis)
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
  "Builds flow/process objects from agent. Reuses conns from topology.
   Injects resilience wrapper nodes into visible-topology for inspection."
  [agent dep-resolvers result-registry resilience-config agent-key]
  (let [{:keys [nodes edges topology]} agent
        agent-resilience (get resilience-config agent-key)
        ;; Inject wrapper nodes into topology for visibility (inspection only)
        visible-topology (resilience/inject-resilience-topology topology agent-resilience)
        deps (:deps topology)
        router-nodes (into #{} (keep (fn [[k v]] (when (has-conditional-routes? v) k))) edges)
        fan-out-nodes (into {} (filter (comp fan-out-node? val)) nodes)
        llm-nodes (into {} (filter (comp llm-node? val)) nodes)

        procs (reduce-kv
               (fn [acc k node]
                 (let [res-config (resilience/resolve-node-config resilience-config agent-key k)]
                   (cond
                     (contains? fan-out-nodes k)
                     (assoc acc k {:proc (flow/process (fan-out-node->step k (get fan-out-nodes k)))})

                     (contains? llm-nodes k)
                     (assoc acc k {:proc (flow/process (llm-node->step k node (get edges k) res-config agent-key))})

                     (not (fn-or-var? node))
                     acc

                     (contains? router-nodes k)
                     (assoc acc k {:proc (flow/process (router-node->step k node (get edges k)))})

                     :else
                     (assoc acc k {:proc (flow/process (pure-node->step node k res-config agent-key))}))))
               {}
               nodes)

        procs (reduce (fn [acc dep-key]
                        (if-let [resolve-fn (get dep-resolvers dep-key)]
                          (let [dep-res-config (resilience/resolve-node-config
                                                resilience-config agent-key dep-key)]
                            (assoc acc dep-key {:proc (flow/process (dep-node->step dep-key resolve-fn dep-res-config agent-key))}))
                          acc))
                      procs
                      deps)

        procs (assoc procs ::output-collector
                     {:proc (flow/process (output-collector-step result-registry
                                                                 (:streaming? topology)))})]

    {:procs procs
     :conns (:conns topology)
     :entry-key (:entry-key topology)
     :deps deps
     :visible-topology visible-topology}))

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
        resilience-config (:resilience opts)
        dep-resolvers (into {}
                            (map (fn [dep-key]
                                   [dep-key (make-dep-resolver ref-resolver wiring agent-key dep-key)]))
                            (:deps topology))
        result-registry (atom {})
        flow-topology (build-flow-procs agent dep-resolvers result-registry resilience-config agent-key)
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
