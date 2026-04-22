(ns ayatori.core
  (:require
   [ayatori.cap :as cap]
   [ayatori.graph.executor :as executor]
   [clojure.core.async :as async]
   [clojure.set :as set]
   [malli.core :as m]
   [malli.error :as me]))

;; Graph Specs

(def Edge
  [:or :keyword [:map-of :keyword :keyword]])

(def ToolSpec
  [:map
   [:name :string]
   [:description {:optional true} :string]
   [:schema {:optional true} :any]])

(def ResponseFormatSpec
  [:map
   [:type [:enum :json-schema :text]]
   [:schema {:optional true} :any]])

(def LLMClientSpec
  [:map
   [:provider :keyword]
   [:model :string]
   [:base-url {:optional true} :string]])

(def LLMNodeConfig
  [:map
   [:prompt {:optional true} :string]
   [:tools {:optional true} [:vector ToolSpec]]
   [:response-format {:optional true} ResponseFormatSpec]
   [:max-turns {:optional true} :int]])

(def LLMNodeWithClient
  [:and
   [:map
    [:type [:= :llm]]
    [:client LLMClientSpec]]
   LLMNodeConfig])

(def LLMNodeWithInvokeFn
  [:and
   [:map
    [:type [:= :llm]]
    [:invoke-fn fn?]
    [:client {:optional true} LLMClientSpec]]
   LLMNodeConfig])

(def LLMNode
  [:or LLMNodeWithClient LLMNodeWithInvokeFn])

(def FanOutNode
  [:map
   [:type [:= :fan-out]]
   [:branches [:vector :keyword]]
   [:strategy {:optional true} [:enum :collect-all :fail-fast]]
   [:collector {:optional true} :keyword]])

(def NodeSpec
  [:or
   [:fn {:error/message "should be a fn or var"}
    (fn [x] (or (fn? x) (and (var? x) (fn? @x))))]
   [:multi {:dispatch :type}
    [:llm LLMNode]
    [:fan-out FanOutNode]]])

(def CapSpec
  [:map
   [:entry :keyword]
   [:input {:optional true} :any]
   [:output {:optional true} :any]])

(def TopologySpec
  [:map
   [:procs [:map-of :keyword :map]]
   [:conns :any]
   [:entry-key :keyword]
   [:deps [:set :keyword]]
   [:node-types {:optional true} [:map-of :keyword [:set :keyword]]]])

(defn- collect-edge-targets [edges]
  (into #{}
        (mapcat (fn [[_ target]]
                  (cond
                    (keyword? target) [target]
                    (map? target) (vals target)
                    :else [])))
        edges))

(defn- caps-entry-in-nodes? [{:keys [nodes caps]}]
  (let [node-set (set (keys nodes))]
    (every? #(contains? node-set (:entry %)) (vals caps))))

(defn- deps-not-in-nodes? [{:keys [nodes deps]}]
  (let [node-set (set (keys nodes))]
    (not-any? node-set (or deps []))))

(defn- no-ayatori-namespace? [{:keys [nodes deps]}]
  (let [user-names (concat (keys nodes) (or deps []))
        has-ayatori-ns? (some #(and (keyword? %) (= "ayatori" (namespace %))) user-names)]
    (not has-ayatori-ns?)))

(defn- edges-target-valid? [{:keys [nodes edges deps]}]
  (let [valid-set (into (set (keys nodes)) (or deps []))
        targets   (collect-edge-targets edges)]
    (every? #(or (= :ayatori/done %) (contains? valid-set %)) targets)))

(defn- collect-fan-out-branches [nodes]
  (reduce-kv
   (fn [acc _ v]
     (if (and (map? v) (= :fan-out (:type v)))
       (into acc (:branches v))
       acc))
   #{}
   nodes))

(defn- no-unreachable-nodes? [{:keys [nodes edges caps]}]
  (let [node-set   (set (keys nodes))
        referenced (into #{} (concat (map :entry (vals caps))
                                     (collect-edge-targets edges)
                                     (collect-fan-out-branches nodes)))]
    (every? #(contains? referenced %) node-set)))

(def GraphSpec
  [:and
   [:map
    [:nodes [:map-of {:min 1} :keyword NodeSpec]]
    [:edges [:map-of :keyword Edge]]
    [:caps [:map-of {:min 1} :keyword CapSpec]]
    [:deps {:optional true} [:vector :keyword]]
    [:max-steps {:optional true} [:int {:min 1}]]]
   [:fn {:error/message "all cap entries must be in :nodes"}
    caps-entry-in-nodes?]
   [:fn {:error/message "deps must not overlap with :nodes"}
    deps-not-in-nodes?]
   [:fn {:error/message ":ayatori/* namespace reserved for framework use"}
    no-ayatori-namespace?]
   [:fn {:error/message "edge targets must reference :nodes, :deps, or :ayatori/done"}
    edges-target-valid?]
   [:fn {:error/message "unreachable nodes detected (not referenced by any cap entry, edge, or fan-out branch)"}
    no-unreachable-nodes?]])

(def BoundGraph
  [:map
   [:name {:optional true} :keyword]
   [:nodes [:map-of {:min 1} :keyword :any]]
   [:edges [:map-of :keyword Edge]]
   [:caps [:map-of {:min 1} :keyword CapSpec]]
   [:deps [:vector :keyword]]
   [:max-steps [:int {:min 1}]]
   [:topology {:optional true} TopologySpec]])

(def WiringTarget [:tuple :keyword :keyword])

(def WiringMap
  [:map-of :keyword [:map-of :keyword WiringTarget]])

(def AgentsMap
  [:map-of :keyword BoundGraph])

(def AddAgentsInput
  [:map
   [:agents AgentsMap]
   [:wiring {:optional true} WiringMap]])

(def SystemConfig
  [:map
   [:agents AgentsMap]
   [:wiring {:optional true} WiringMap]
   [:host {:optional true} :string]
   [:port {:optional true} :int]])

;; System Runtime Schemas

(def AgentEntry
  [:map
   [:graph BoundGraph]
   [:flow {:optional true} :any]])

(def AgentsAtomContent
  [:map-of :keyword AgentEntry])

(def SystemState
  [:map
   [:status [:enum :stopped :running]]
   [:caps {:optional true} [:map-of :keyword [:map-of :keyword :any]]]
   [:resolver {:optional true} fn?]])

(defn- atom-of [schema]
  [:fn {:error/message (str "must be atom of " (m/form schema))}
   (fn [v] (and (instance? clojure.lang.Atom v)
                (m/validate schema @v)))])

(def AgentSystem
  [:map
   [:agents (atom-of AgentsAtomContent)]
   [:wiring (atom-of WiringMap)]
   [:host :string]
   [:port :int]
   [:state (atom-of SystemState)]])

;; Executor

(defn- execute-graph
  "Executes a graph via flow. Flow must be created at system start."
  [flow-state input opts]
  (when-not flow-state
    (throw (ex-info "No flow for agent (system not started?)" {:agent (:agent opts)})))
  (executor/inject flow-state (:entry opts) input (:streaming? opts)))

;; System

(defonce ^:private active-system (atom nil))

(defn make-agent
  "Creates an agent from a spec. Validates and builds topology for inspection."
  {:malli/schema [:=> [:cat GraphSpec] BoundGraph]}
  [spec]
  (when-not (m/validate GraphSpec spec)
    (throw (ex-info "Invalid graph spec"
                    {:errors (me/humanize (m/explain GraphSpec spec))})))
  (let [agent {:nodes (:nodes spec)
               :edges (or (:edges spec) {})
               :caps (:caps spec)
               :deps (or (:deps spec) [])
               :max-steps (or (:max-steps spec) 100)}
        topology (executor/build-topology-spec agent)]
    (assoc agent :topology topology)))

(defn- wrap-agent [agent-key agent]
  {:graph (assoc agent :name agent-key)})

(defn- flatten-agents
  "Wraps agents with their names."
  [agents]
  (into {} (map (fn [[k v]] [k (wrap-agent k v)])) agents))

(defn make-system
  "Creates an agent system from a config map."
  {:malli/schema [:=> [:cat SystemConfig] AgentSystem]}
  [{:keys [agents wiring host port]}]
  {:agents (atom (flatten-agents agents))
   :wiring (atom (or wiring {}))
   :host (or host "localhost")
   :port (or port 9000)
   :state (atom {:status :stopped})})

(defn- validate-schema! [schema data direction]
  (when-not (m/validate schema data)
    (throw (ex-info (str "Cap " direction " validation failed")
                    {:errors   (me/humanize (m/explain schema data))
                     direction data}))))

(defn- caps->handles [agent-key caps sys-host sys-port]
  (into {}
        (map (fn [[cap-key cap-config]]
               (let [uri (cap/make-uri sys-host sys-port agent-key cap-key)]
                 [cap-key (cap/make-cap-handle uri {:agent agent-key
                                                    :cap cap-key
                                                    :input (:input cap-config)
                                                    :output (:output cap-config)})])))
        caps))

(defn- build-cap-map [agents-map sys-host sys-port]
  (into {}
        (map (fn [[agent-key {:keys [graph]}]]
               [agent-key (caps->handles agent-key (:caps graph) sys-host sys-port)]))
        agents-map))

(defn- local-uri? [uri-host uri-port sys-host sys-port]
  (and (= uri-host sys-host) (= uri-port sys-port)))

(defn- resolve-local [sys agent cap input]
  (let [agent-entry (get @(:agents sys) agent)
        {:keys [graph flow]} agent-entry
        cap-config (get-in graph [:caps cap])]
    (when-not agent-entry
      (throw (ex-info "Agent not found" {:agent agent})))
    (when-not cap-config
      (throw (ex-info "Cap not found" {:agent agent :cap cap})))
    (when (:input cap-config)
      (validate-schema! (:input cap-config) input :input))
    (execute-graph flow input
                   {:agent agent
                    :entry (:entry cap-config)})))

(defn- make-resolver [sys]
  (fn resolver
    ([uri input] (resolver uri input {}))
    ([uri input _caller-opts]
     (let [{:keys [host port agent cap]} (cap/parse-uri uri)]
       (if (local-uri? host port (:host sys) (:port sys))
         (resolve-local sys agent cap input)
         (throw (ex-info "Remote execution not yet supported" {:host host :port port})))))))

(defn- make-ref-resolver [sys]
  (fn [[target-agent target-cap] input]
    (resolve-local sys target-agent target-cap input)))

(defn- start-agent-flows!
  "Creates and starts flows for agents that support flow execution."
  [sys]
  (let [ref-resolver (make-ref-resolver sys)]
    (doseq [[agent-key {:keys [graph]}] @(:agents sys)]
      (when (executor/supports-graph? graph)
        (let [flow-state (executor/create-agent-flow
                          graph
                          {:ref-resolver ref-resolver
                           :wiring (:wiring sys)
                           :agent agent-key})]
          (swap! (:agents sys) assoc-in [agent-key :flow] flow-state))))))

(defn- stop-agent-flows!
  "Stops all agent flows."
  [sys]
  (doseq [[agent-key {:keys [flow]}] @(:agents sys)]
    (when flow
      (executor/stop-agent-flow flow)
      (swap! (:agents sys) update agent-key dissoc :flow))))

(defn start!
  "Starts the system. Builds cap-map, resolver, and agent flows."
  {:malli/schema [:=> [:cat AgentSystem] AgentSystem]}
  [sys]
  (when @active-system
    (throw (ex-info "A system is already running. Stop it before starting a new one." {})))
  (let [cap-map (build-cap-map @(:agents sys) (:host sys) (:port sys))
        resolver (make-resolver sys)]
    (reset! (:state sys) {:status :running
                          :caps cap-map
                          :resolver resolver})
    (start-agent-flows! sys)
    (reset! active-system sys)
    sys))

(defn stop!
  "Stops the system. Stops all agent flows."
  {:malli/schema [:=> [:cat AgentSystem] AgentSystem]}
  [sys]
  (when (= :running (:status @(:state sys)))
    (stop-agent-flows! sys))
  (reset! (:state sys) {:status :stopped})
  (reset! active-system nil)
  sys)

(defn- get-agent-flow [sys agent-key]
  (get-in @(:agents sys) [agent-key :flow :flow]))

(defn pause-agent!
  "Pauses an agent's flow. Messages queue but don't process until resumed."
  [sys agent-key]
  (when-let [flow (get-agent-flow sys agent-key)]
    (executor/pause-flow flow)))

(defn resume-agent!
  "Resumes a paused agent's flow."
  [sys agent-key]
  (when-let [flow (get-agent-flow sys agent-key)]
    (executor/resume-flow flow)))

(defn ping-agent
  "Health check for an agent's flow. Returns channel with ping result."
  [sys agent-key]
  (when-let [flow (get-agent-flow sys agent-key)]
    (executor/ping-flow flow)))

(defn caps
  "Returns the nested CapHandle map: {:agent-key {:cap-key <CapHandle>}}."
  {:malli/schema [:=> [:cat AgentSystem] [:map-of :keyword [:map-of :keyword :any]]]}
  [sys]
  (:caps @(:state sys)))

(defn describe-topology
  "Returns the topology spec for an agent. Available before start!.
   Useful for inspection, visualization, and debugging."
  [agent]
  (or (get-in agent [:graph :topology])
      (:topology agent)))

(defn orphans
  "Returns set of agent keys that no other agent depends on."
  {:malli/schema [:=> [:cat AgentSystem] [:set :keyword]]}
  [sys]
  (let [all-agents (set (keys @(:agents sys)))
        wiring @(:wiring sys)
        depended-on (->> wiring vals (mapcat vals) (map first) set)]
    (set/difference all-agents depended-on)))

(defn describe-system-topology
  "Returns topology for entire system: all agents, their graphs, and wiring.
   Available before or after start!. Useful for system-wide visualization."
  [sys]
  (let [agents-map @(:agents sys)
        wiring @(:wiring sys)
        agent-topologies (into {}
                               (map (fn [[k {:keys [graph]}]]
                                      [k (or (:topology graph) (:caps graph))]))
                               agents-map)
        wiring-edges (for [[caller-key caller-wiring] wiring
                           [dep-key [target-agent target-cap]] caller-wiring]
                       {:from [caller-key dep-key]
                        :to [target-agent target-cap]})]
    {:agents agent-topologies
     :wiring wiring
     :edges (vec wiring-edges)
     :orphans (orphans sys)}))

(defn- register-single-agent! [sys agent-key agent running?]
  (let [agent-entry (wrap-agent agent-key agent)
        cap-handles (when running?
                      (reduce-kv
                       (fn [acc cap-key cap-config]
                         (let [uri (cap/make-uri (:host sys) (:port sys) agent-key cap-key)]
                           (assoc acc cap-key
                                  (cap/make-cap-handle uri {:agent agent-key
                                                            :cap cap-key
                                                            :input (:input cap-config)
                                                            :output (:output cap-config)}))))
                       {}
                       (:caps agent)))]
    (swap! (:agents sys) assoc agent-key agent-entry)
    (when running?
      (swap! (:state sys) assoc-in [:caps agent-key] cap-handles))
    agent-entry))

(defn add-agents!
  "Adds agents to a running or stopped system."
  {:malli/schema [:=> [:cat AgentSystem AddAgentsInput] AgentSystem]}
  [sys {:keys [agents wiring]}]
  (let [running? (= :running (:status @(:state sys)))
        added-keys (atom [])]
    (doseq [[agent-key _] agents]
      (when (get @(:agents sys) agent-key)
        (throw (ex-info "Agent already exists" {:agent agent-key}))))
    (try
      (doseq [[agent-key agent-wiring] wiring]
        (swap! (:wiring sys) assoc agent-key agent-wiring))
      (doseq [[agent-key agent] agents]
        (register-single-agent! sys agent-key agent running?)
        (swap! added-keys conj agent-key))
      (catch Throwable e
        (doseq [k @added-keys]
          (swap! (:agents sys) dissoc k)
          (swap! (:state sys) update :caps dissoc k))
        (doseq [[agent-key _] wiring]
          (swap! (:wiring sys) dissoc agent-key))
        (throw e)))
    sys))

(defn- find-dependents
  "Returns map of dependents in wiring format: {caller {dep-key [target-agent cap]}}."
  [sys agent-key]
  (into {}
        (keep (fn [[caller-key caller-wiring]]
                (let [deps (into {}
                                 (filter (fn [[_ [target _]]] (= agent-key target)))
                                 caller-wiring)]
                  (when (seq deps) [caller-key deps]))))
        @(:wiring sys)))

(defn- validate-rewire-plan
  "Validates rewire plan covers all dependents and targets exist."
  [sys dependents rewire-plan]
  (doseq [[caller-key dep-map] dependents
          [dep-key wiring] dep-map]
    (let [new-target (get-in rewire-plan [caller-key dep-key])]
      (when-not new-target
        (throw (ex-info (str "Rewire plan missing: " caller-key " needs " dep-key
                             " (currently " (pr-str {caller-key {dep-key wiring}}) ")")
                        {:caller caller-key :dep dep-key :current-wiring wiring})))))
  (doseq [[_ dep-map] rewire-plan
          [dep-key [target-agent target-cap]] dep-map]
    (let [target (get @(:agents sys) target-agent)]
      (when-not target
        (throw (ex-info (str "Rewire target agent not found: " target-agent
                             " (for dep " dep-key ")")
                        {:agent target-agent :dep dep-key})))
      (when-not (get-in target [:graph :caps target-cap])
        (throw (ex-info (str "Rewire target cap not found: " target-agent "/" target-cap
                             " (for dep " dep-key ")")
                        {:agent target-agent :cap target-cap :dep dep-key}))))))

(defn remove-agent!
  "Removes an agent from the system. If other agents depend on it, a :rewire plan must be provided."
  [sys agent-key & {:keys [rewire]}]
  (let [agent-entry (get @(:agents sys) agent-key)]
    (when-not agent-entry
      (throw (ex-info "Agent not found" {:agent agent-key})))
    (let [dependents (find-dependents sys agent-key)]
      (when (seq dependents)
        (when-not rewire
          (throw (ex-info (str "Cannot remove agent " agent-key
                               ", depended on by: " (pr-str dependents))
                          {:agent agent-key
                           :dependents dependents})))
        (validate-rewire-plan sys dependents rewire))
      (doseq [[caller-key dep-map] rewire
              [dep-key target] dep-map]
        (swap! (:wiring sys) assoc-in [caller-key dep-key] target))
      (swap! (:agents sys) dissoc agent-key)
      (swap! (:state sys) update :caps dissoc agent-key)
      (swap! (:wiring sys) dissoc agent-key)
      sys)))

(defn rewire!
  "Changes dep wiring at runtime. Next dep resolution uses new target."
  {:malli/schema [:=> [:cat AgentSystem :keyword [:map-of :keyword WiringTarget]] :nil]}
  [sys agent-key dep-bindings]
  (when-not (= :running (:status @(:state sys)))
    (throw (ex-info "System not started" {:status (:status @(:state sys))})))
  (doseq [[dep-key [target-agent target-cap]] dep-bindings]
    (let [target-entry (get @(:agents sys) target-agent)]
      (when-not target-entry
        (throw (ex-info "Rewire target agent not found" {:agent target-agent})))
      (when-not (get-in target-entry [:graph :caps target-cap])
        (throw (ex-info "Rewire target cap not found" {:agent target-agent :cap target-cap}))))
    (swap! (:wiring sys) assoc-in [agent-key dep-key] [target-agent target-cap])))

(defn- lookup-cap [sys agent-key cap-key]
  (let [agent-entry (get @(:agents sys) agent-key)]
    (when-not agent-entry
      (throw (ex-info "Agent not found in system"
                      {:agent agent-key :agents (keys @(:agents sys))})))
    (let [{:keys [graph flow]} agent-entry
          cap-config (get-in graph [:caps cap-key])]
      (when-not cap-config
        (throw (ex-info "Cap not found"
                        {:agent agent-key
                         :cap   cap-key
                         :caps  (keys (:caps graph))})))
      {:graph graph :flow flow :cap cap-config})))

(defn- wrap-output-validation [ch output-schema]
  (if output-schema
    (async/go
      (let [result (async/<! ch)]
        (if (instance? Throwable result)
          result
          (try
            (validate-schema! output-schema result :output)
            result
            (catch Throwable e e)))))
    ch))

(defn run
  "Executes an agent cap within a started system. Returns a channel."
  {:malli/schema [:=> [:cat AgentSystem :keyword :keyword :any] :any]}
  [sys agent-key cap-key input]
  (when-not (= :running (:status @(:state sys)))
    (throw (ex-info "System not started" {:status (:status @(:state sys))})))
  (let [{:keys [graph flow cap]} (lookup-cap sys agent-key cap-key)
        entry-node (get-in graph [:nodes (:entry cap)])
        streaming? (and (map? entry-node) (:stream entry-node))]
    (when (:input cap)
      (validate-schema! (:input cap) input :input))
    (if streaming?
      (execute-graph flow input
                     {:agent agent-key
                      :entry (:entry cap)
                      :streaming? true})
      (-> (execute-graph flow input
                         {:agent agent-key
                          :entry (:entry cap)})
          (wrap-output-validation (:output cap))))))
