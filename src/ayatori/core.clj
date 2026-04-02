(ns ayatori.core
  (:require
   [ayatori.cap :as cap]
   [ayatori.graph.executor :as executor]
   [ayatori.graph.store :as store]
   [clojure.core.async :as async :refer [<!]]
   [malli.core :as m]
   [malli.error :as me]))

;; Graph Specs

(def Edge
  [:or :keyword [:map-of :keyword :keyword]])

(def LifecycleConfig
  [:map
   [:on-start {:optional true} fn?]])

(def LLMClientSpec
  [:map
   [:provider :keyword]
   [:model :string]
   [:base-url {:optional true} :string]])

(def LLMNodeConfig
  [:map
   [:prompt {:optional true} :string]
   [:tools {:optional true} [:vector :map]]
   [:stream {:optional true} :boolean]
   [:response-format {:optional true} :map]
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
    [:client {:optional true} :map]]
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
   fn?
   [:multi {:dispatch :type}
    [:llm LLMNode]
    [:fan-out FanOutNode]]])

(def CapSpec
  [:map
   [:entry :keyword]
   [:input {:optional true} :any]
   [:output {:optional true} :any]])

(defn- collect-edge-targets [edges]
  (reduce-kv
   (fn [acc _from target]
     (cond
       (keyword? target) (conj acc target)
       (map? target) (into acc (vals target))
       :else acc))
   #{}
   edges))

(defn- caps-entry-in-nodes? [{:keys [nodes caps]}]
  (let [node-set (set (keys nodes))]
    (every? #(contains? node-set (:entry %)) (vals caps))))

(defn- deps-not-in-nodes? [{:keys [nodes deps]}]
  (let [node-set (set (keys nodes))]
    (every? #(not (contains? node-set %)) (or deps []))))

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
        referenced (reduce into #{} [(map :entry (vals caps))
                                     (collect-edge-targets edges)
                                     (collect-fan-out-branches nodes)])]
    (every? #(contains? referenced %) node-set)))

(def GraphSpec
  [:and
   [:map
    [:nodes [:map-of {:min 1} :keyword NodeSpec]]
    [:edges [:map-of :keyword Edge]]
    [:caps [:map-of {:min 1} :keyword CapSpec]]
    [:deps {:optional true} [:vector :keyword]]
    [:max-steps {:optional true} [:int {:min 1}]]
    [:lifecycle {:optional true} LifecycleConfig]]
   [:fn {:error/message "all cap entries must be in :nodes"}
    caps-entry-in-nodes?]
   [:fn {:error/message "deps must not overlap with :nodes"}
    deps-not-in-nodes?]
   [:fn {:error/message "edge targets must reference :nodes, :deps, or :ayatori/done"}
    edges-target-valid?]
   [:fn {:error/message "unreachable nodes detected (not referenced by any cap entry, edge, or fan-out branch)"}
    no-unreachable-nodes?]])

(def CompiledGraph
  [:map
   [:type [:= :compiled-graph]]
   [:name {:optional true} :keyword]
   [:nodes [:vector {:min 1} :keyword]]
   [:edges [:map-of :keyword Edge]]
   [:caps [:map-of {:min 1} :keyword CapSpec]]
   [:deps [:vector :keyword]]
   [:max-steps [:int {:min 1}]]
   [:lifecycle {:optional true} LifecycleConfig]])

(def BoundGraph
  [:map
   [:compiled CompiledGraph]
   [:nodes [:map-of :keyword :any]]])

(def StoreConfig
  [:map
   [:type :keyword]
   [:path {:optional true} :string]])

(def WiringTarget [:tuple :keyword :keyword])

(def SystemConfig
  [:map
   [:agents [:map-of :keyword BoundGraph]]
   [:middleware {:optional true} [:vector :any]]
   [:wiring {:optional true} [:map-of :keyword [:map-of :keyword WiringTarget]]]
   [:store {:optional true} StoreConfig]
   [:host {:optional true} :string]
   [:port {:optional true} :int]])

;; Graph Compilation

(defn- compile-graph
  "Validates spec and produces a compiled graph. Name is assigned later when added to system."
  [spec]
  (when-not (m/validate GraphSpec spec)
    (throw (ex-info "Invalid graph spec"
                    {:errors (me/humanize (m/explain GraphSpec spec))})))
  (let [node-impls (:nodes spec)]
    {:compiled (cond-> {:type :compiled-graph
                        :nodes (vec (keys node-impls))
                        :edges (or (:edges spec) {})
                        :caps (:caps spec)
                        :deps (or (:deps spec) [])
                        :max-steps (or (:max-steps spec) 100)}
                 (:lifecycle spec) (assoc :lifecycle (:lifecycle spec)))
     :nodes node-impls}))

;; System

(defonce ^:private active-system (atom nil))

(defn make-agent
  "Creates an agent from a spec. Validates topology and binds node implementations."
  {:malli/schema [:=> [:cat GraphSpec] BoundGraph]}
  [spec]
  (compile-graph spec))

(defn- wrap-agent [agent-key agent]
  {:graph (assoc-in agent [:compiled :name] agent-key)
   :state (atom nil)})

(defn- flatten-agents
  "Wraps agents with their names and state atoms."
  [agents]
  (reduce-kv
   (fn [acc agent-key agent]
     (assoc acc agent-key (wrap-agent agent-key agent)))
   {}
   agents))

(defn make-system
  "Creates an agent system from a config map."
  {:malli/schema [:=> [:cat SystemConfig] :map]}
  [{:keys [agents middleware wiring store host port]}]
  {:agents (atom (flatten-agents agents))
   :middleware (or middleware [])
   :wiring (atom (or wiring {}))
   :store (if store (store/make-store store) (store/make-store))
   :host (or host "localhost")
   :port (or port 9000)
   :state (atom {:status :stopped})})

(defn- validate-schema! [schema data direction]
  (when-not (m/validate schema data)
    (throw (ex-info (str "Cap " direction " validation failed")
                    {:errors   (me/humanize (m/explain schema data))
                     direction data}))))

(defn- build-cap-map [agents-map sys-host sys-port]
  (reduce-kv
   (fn [acc agent-key {:keys [graph]}]
     (let [caps (get-in graph [:compiled :caps])
           cap-handles (reduce-kv
                        (fn [eacc cap-key cap-config]
                          (let [uri (cap/make-uri sys-host sys-port agent-key cap-key)]
                            (assoc eacc cap-key
                                   (cap/make-cap-handle uri
                                                        {:agent agent-key
                                                         :cap cap-key
                                                         :input (:input cap-config)
                                                         :output (:output cap-config)}))))
                        {} caps)]
       (assoc acc agent-key cap-handles)))
   {} agents-map))

(defn- local-uri? [uri-host uri-port sys-host sys-port]
  (and (= uri-host sys-host) (= uri-port sys-port)))

(defn- make-resolver [sys]
  (fn resolver
    ([uri input] (resolver uri input {}))
    ([uri input caller-opts]
     (let [{:keys [host port agent cap]} (cap/parse-uri uri)]
       (if (local-uri? host port (:host sys) (:port sys))
         (let [agent-entry (get @(:agents sys) agent)
               {:keys [graph state]} agent-entry
               cap-config (get-in graph [:compiled :caps cap])]
           (when-not agent-entry
             (throw (ex-info "Agent not found" {:agent agent})))
           (when-not cap-config
             (throw (ex-info "Cap not found" {:agent agent :cap cap})))
           (when (:input cap-config)
             (validate-schema! (:input cap-config) input :input))
           (executor/execute graph (:store sys) input
                             (merge (select-keys caller-opts [:trace-id :span-id :path])
                                    {:middleware (:middleware sys)
                                     :agent agent
                                     :entry (:entry cap-config)
                                     :resolver resolver
                                     :wiring @(:wiring sys)
                                     :agents @(:agents sys)
                                     :agent-state state
                                     :sys-host (:host sys)
                                     :sys-port (:port sys)})))
         (throw (ex-info "Remote execution not yet supported" {:host host :port port})))))))

(defn- run-lifecycle-hooks! [sys]
  (let [agents-map @(:agents sys)
        cap-map (:caps @(:state sys))]
    (doseq [[agent-key {:keys [graph state]}] agents-map]
      (when-let [on-start (get-in graph [:compiled :lifecycle :on-start])]
        (let [deps (get-in graph [:compiled :deps])
              wiring @(:wiring sys)
              resolved-deps (reduce
                             (fn [acc dep-key]
                               (if-let [[target-agent target-cap] (get-in wiring [agent-key dep-key])]
                                 (assoc acc dep-key (get-in cap-map [target-agent target-cap]))
                                 acc))
                             {}
                             deps)
              ctx {:agent-key agent-key
                   :caps (get cap-map agent-key)
                   :deps resolved-deps}
              init-state (on-start ctx)]
          (reset! state init-state))))))

(defn start!
  "Starts the system. Builds cap-map and resolver."
  {:malli/schema [:=> [:cat :map] :map]}
  [sys]
  (when @active-system
    (throw (ex-info "A system is already running. Stop it before starting a new one." {})))
  (let [cap-map (build-cap-map @(:agents sys) (:host sys) (:port sys))
        resolver (make-resolver sys)]
    (reset! (:state sys) {:status :running
                          :caps cap-map
                          :resolver resolver})
    (run-lifecycle-hooks! sys)
    (reset! active-system sys)
    sys))

(defn stop!
  "Stops the system."
  {:malli/schema [:=> [:cat :map] :map]}
  [sys]
  (reset! (:state sys) {:status :stopped})
  (reset! active-system nil)
  sys)

(defn caps
  "Returns the nested CapHandle map: {:agent-key {:cap-key <CapHandle>}}."
  {:malli/schema [:=> [:cat :map] [:map-of :keyword [:map-of :keyword :any]]]}
  [sys]
  (:caps @(:state sys)))

(defn- register-single-agent! [sys agent-key agent running?]
  (let [agent-entry (wrap-agent agent-key agent)
        cap-handles (when running?
                      (reduce-kv
                       (fn [acc cap-key cap-config]
                         (let [uri (cap/make-uri (:host sys) (:port sys) agent-key cap-key)]
                           (assoc acc cap-key
                                  (cap/make-cap-handle uri
                                                       {:agent agent-key
                                                        :cap cap-key
                                                        :input (:input cap-config)
                                                        :output (:output cap-config)}))))
                       {}
                       (get-in agent [:compiled :caps])))]
    (swap! (:agents sys) assoc agent-key agent-entry)
    (when running?
      (swap! (:state sys) assoc-in [:caps agent-key] cap-handles))
    agent-entry))

(defn- run-agent-lifecycle! [sys agent-key agent-entry agent]
  (when-let [on-start (get-in agent [:compiled :lifecycle :on-start])]
    (let [deps (get-in agent [:compiled :deps])
          resolved-deps (reduce
                         (fn [acc dep-key]
                           (if-let [[target-agent target-cap] (get-in @(:wiring sys) [agent-key dep-key])]
                             (assoc acc dep-key (get-in (:caps @(:state sys)) [target-agent target-cap]))
                             acc))
                         {}
                         deps)
          cap-handles (get-in @(:state sys) [:caps agent-key])
          ctx {:agent-key agent-key
               :caps cap-handles
               :deps resolved-deps}
          init-state (on-start ctx)]
      (reset! (:state agent-entry) init-state))))

(defn add-agents!
  "Adds agents to a running or stopped system. Runs :on-start hooks if system is running."
  {:malli/schema [:=> [:cat :map [:map
                                  [:agents [:map-of :keyword BoundGraph]]
                                  [:wiring {:optional true} [:map-of :keyword [:map-of :keyword WiringTarget]]]]]
                  :map]}
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
        (let [agent-entry (register-single-agent! sys agent-key agent running?)]
          (swap! added-keys conj agent-key)
          (when running?
            (run-agent-lifecycle! sys agent-key agent-entry agent))))
      (catch Throwable e
        (doseq [k @added-keys]
          (swap! (:agents sys) dissoc k)
          (swap! (:state sys) update :caps dissoc k))
        (doseq [[agent-key _] wiring]
          (swap! (:wiring sys) dissoc agent-key))
        (throw e)))
    sys))

(defn rewire!
  "Changes dep wiring at runtime. Next dep resolution uses new target."
  {:malli/schema [:=> [:cat :map :keyword [:map-of :keyword WiringTarget]] :nil]}
  [sys agent-key dep-bindings]
  (when-not (= :running (:status @(:state sys)))
    (throw (ex-info "System not started" {:status (:status @(:state sys))})))
  (doseq [[dep-key [target-agent target-cap]] dep-bindings]
    (let [target-entry (get @(:agents sys) target-agent)]
      (when-not target-entry
        (throw (ex-info "Rewire target agent not found" {:agent target-agent})))
      (when-not (get-in target-entry [:graph :compiled :caps target-cap])
        (throw (ex-info "Rewire target cap not found" {:agent target-agent :cap target-cap}))))
    (swap! (:wiring sys) assoc-in [agent-key dep-key] [target-agent target-cap])))

(defn- lookup-cap [sys agent-key cap-key]
  (let [agent-entry (get @(:agents sys) agent-key)]
    (when-not agent-entry
      (throw (ex-info "Agent not found in system"
                      {:agent agent-key :agents (keys @(:agents sys))})))
    (let [{:keys [graph state]} agent-entry
          cap-config (get-in graph [:compiled :caps cap-key])]
      (when-not cap-config
        (throw (ex-info "Cap not found"
                        {:agent agent-key
                         :cap   cap-key
                         :caps  (keys (get-in graph [:compiled :caps]))})))
      {:graph graph :state state :cap cap-config})))

(defn- wrap-output-validation [ch output-schema]
  (if output-schema
    (async/go
      (let [result (<! ch)]
        (if (instance? Throwable result)
          result
          (try
            (validate-schema! output-schema result :output)
            result
            (catch Throwable e e)))))
    ch))

(defn run
  "Executes an agent cap within a started system. Returns a channel."
  {:malli/schema [:=> [:cat :map :keyword :keyword :any] :any]}
  [sys agent-key cap-key input]
  (when-not (= :running (:status @(:state sys)))
    (throw (ex-info "System not started" {:status (:status @(:state sys))})))
  (let [{:keys [graph state cap]} (lookup-cap sys agent-key cap-key)]
    (when (:input cap)
      (validate-schema! (:input cap) input :input))
    (-> (executor/execute graph (:store sys) input
                          {:middleware   (:middleware sys)
                           :agent        agent-key
                           :cap          cap-key
                           :entry        (:entry cap)
                           :resolver     (:resolver @(:state sys))
                           :wiring       @(:wiring sys)
                           :agents       @(:agents sys)
                           :agent-state  state
                           :sys-host     (:host sys)
                           :sys-port     (:port sys)})
        (wrap-output-validation (:output cap)))))
