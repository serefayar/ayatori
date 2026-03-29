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

(def StatefulNode
  [:map
   [:type [:= :stateful]]
   [:handler fn?]
   [:init-state :map]])

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
   [:strategy {:optional true} [:enum :collect-all :fail-fast]]])

(def AgentNode
  [:map
   [:type [:= :agent]]
   [:agent :map]
   [:cap :keyword]])

(def NodeSpec
  [:or
   fn?
   [:multi {:dispatch :type}
    [:stateful StatefulNode]
    [:llm LLMNode]
    [:fan-out FanOutNode]
    [:agent AgentNode]]])

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

(defn- agent-node? [v]
  (and (map? v) (= :agent (:type v))))

(defn- agent-nodes-valid? [{:keys [nodes]}]
  (every? (fn [[_ v]]
            (if (agent-node? v)
              (let [g (:agent v)
                    c (:cap v)]
                (and (map? g)
                     (contains? g :compiled)
                     (keyword? c)
                     (contains? (get-in g [:compiled :caps]) c)))
              true))
          nodes))

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
    [:max-steps {:optional true} [:int {:min 1}]]]
   [:fn {:error/message "all cap entries must be in :nodes"}
    caps-entry-in-nodes?]
   [:fn {:error/message "deps must not overlap with :nodes"}
    deps-not-in-nodes?]
   [:fn {:error/message "edge targets must reference :nodes, :deps, or :ayatori/done"}
    edges-target-valid?]
   [:fn {:error/message ":agent nodes must have a valid compiled graph and cap"}
    agent-nodes-valid?]
   [:fn {:error/message "unreachable nodes detected (not referenced by any cap entry, edge, or fan-out branch)"}
    no-unreachable-nodes?]])

(def CompiledGraph
  [:map
   [:type [:= :compiled-graph]]
   [:nodes [:vector {:min 1} :keyword]]
   [:edges [:map-of :keyword Edge]]
   [:caps [:map-of {:min 1} :keyword CapSpec]]
   [:deps [:vector :keyword]]
   [:max-steps [:int {:min 1}]]])

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
   [:store {:optional true} StoreConfig]])


;; Graph Compilation

(defn- compile-graph
  "Validates spec and produces a bound graph (compiled topology + node impls)."
  [spec]
  (when-not (m/validate GraphSpec spec)
    (throw (ex-info "Invalid graph spec"
                    {:errors (me/humanize (m/explain GraphSpec spec))})))
  (let [node-impls (:nodes spec)]
    {:compiled {:type :compiled-graph
                :nodes (vec (keys node-impls))
                :edges (or (:edges spec) {})
                :caps (:caps spec)
                :deps (or (:deps spec) [])
                :max-steps (or (:max-steps spec) 100)}
     :nodes    node-impls}))


;; System

(defonce ^:private active-system (atom nil))

(defn make-agent
  "Creates an agent from a spec. Validates topology and binds node implementations."
  {:malli/schema [:=> [:cat GraphSpec] BoundGraph]}
  [spec]
  (compile-graph spec))

(defn make-system
  "Creates an agent system from a config map."
  {:malli/schema [:=> [:cat SystemConfig] :map]}
  [{:keys [agents middleware wiring store]}]
  {:agents agents
   :middleware (or middleware [])
   :wiring (or wiring {})
   :store (if store (store/make-store store) (store/make-store))
   :state (atom {:status :stopped})})

(defn- validate-schema! [schema data direction]
  (when-not (m/validate schema data)
    (throw (ex-info (str "Cap " direction " validation failed")
                    {:errors   (me/humanize (m/explain schema data))
                     direction data}))))

(defn- build-registry-entry [agent-key cap-key cap-config]
  {:agent agent-key
   :cap cap-key
   :entry (:entry cap-config)
   :input-schema (:input cap-config)
   :output-schema (:output cap-config)})

(defn- register-cap! [registry agent-key cap-key cap-config]
  (let [ref (cap/make-ref)
        uri (cap/make-uri "local" -1 ref)]
    (swap! registry assoc ref (build-registry-entry agent-key cap-key cap-config))
    (cap/make-cap-handle uri
                         {:agent agent-key
                          :cap cap-key
                          :input (:input cap-config)
                          :output (:output cap-config)})))

(defn- build-cap-map [agents registry]
  (reduce-kv
   (fn [acc agent-key agent]
     (let [caps (get-in agent [:compiled :caps])
           cap-handles (reduce-kv
                        (fn [eacc cap-key cap-config]
                          (assoc eacc cap-key
                                 (register-cap! registry agent-key cap-key cap-config)))
                        {} caps)]
       (assoc acc agent-key cap-handles)))
   {} agents))

(defn- find-registry-ref [registry agent-key cap-key]
  (some (fn [[ref entry]]
          (when (and (= agent-key (:agent entry))
                     (= cap-key (:cap entry)))
            ref))
        @registry))

(defn- resolve-cap [registry uri]
  (let [{:keys [ref]} (cap/parse-uri uri)]
    (or (get @registry ref)
        (throw (ex-info "Unknown capability ref" {:uri uri :ref ref})))))

(defn- make-resolver [sys registry]
  (fn resolver
    ([uri input] (resolver uri input {}))
    ([uri input caller-opts]
     (let [reg-entry (resolve-cap registry uri)
           {:keys [agent entry input-schema]} reg-entry
           ag (get (:agents sys) agent)]
       (when input-schema
         (validate-schema! input-schema input :input))
       (executor/execute ag (:store sys) input
                         (merge (select-keys caller-opts [:trace-id :span-id :path])
                                {:middleware (:middleware sys)
                                 :agent      agent
                                 :entry      entry
                                 :resolver   resolver}))))))

(defn- inject-dep [registry wiring agent-key dep-key]
  (let [target (get-in wiring [agent-key dep-key])]
    (when-not target
      (throw (ex-info "Unresolved dep: no wiring found"
                      {:agent agent-key
                       :dep dep-key
                       :available-wiring (keys (get wiring agent-key))})))
    (let [[target-agent target-cap] target
          ref (find-registry-ref registry target-agent target-cap)]
      (when-not ref
        (throw (ex-info "Wiring target not found in registry"
                        {:agent agent-key
                         :dep dep-key
                         :target [target-agent target-cap]})))
      (cap/make-cap-handle (cap/make-uri "local" -1 ref) {}))))

(defn- inject-all-deps [agents registry wiring]
  (reduce-kv
   (fn [acc agent-key agent]
     (let [deps (get-in agent [:compiled :deps])]
       (if (seq deps)
         (let [dep-nodes (reduce (fn [m dep-key]
                                   (assoc m dep-key
                                          (inject-dep registry wiring agent-key dep-key)))
                                 {} deps)]
           (assoc acc agent-key (update agent :nodes merge dep-nodes)))
         (assoc acc agent-key agent))))
   {} agents))

(defn start!
  "Starts the system. Creates ref registry, per-cap CapHandles, resolves deps."
  {:malli/schema [:=> [:cat :map] :map]}
  [sys]
  (when @active-system
    (throw (ex-info "A system is already running. Stop it before starting a new one." {})))
  (let [registry (atom {})
        resolver (make-resolver sys registry)
        cap-map  (build-cap-map (:agents sys) registry)
        agents   (inject-all-deps (:agents sys) registry (:wiring sys))
        started  (assoc sys :agents agents)]
    (reset! (:state sys) {:status :running
                          :caps cap-map
                          :registry registry
                          :resolver resolver})
    (reset! active-system started)
    started))

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

(defn rewire!
  "Changes dep wiring at runtime. Updates registry so next execution uses new target."
  {:malli/schema [:=> [:cat :map :keyword [:map-of :keyword WiringTarget]] :nil]}
  [sys agent-key dep-bindings]
  (when-not (= :running (:status @(:state sys)))
    (throw (ex-info "System not started" {:status (:status @(:state sys))})))
  (let [registry (:registry @(:state sys))]
    (doseq [[dep-key [target-agent target-cap]] dep-bindings]
      (let [agent (get (:agents sys) agent-key)
            dep-ch (get (:nodes agent) dep-key)]
        (when-not (cap/cap-handle? dep-ch)
          (throw (ex-info "Cannot rewire: not a dep node"
                          {:agent agent-key :node dep-key})))
        (let [old-ref (:ref (cap/parse-uri (cap/cap-uri dep-ch)))
              new-ref (find-registry-ref registry target-agent target-cap)]
          (when-not new-ref
            (throw (ex-info "Rewire target not found"
                            {:target [target-agent target-cap]})))
          (let [new-entry (get @registry new-ref)]
            (swap! registry assoc old-ref new-entry)))))))

(defn- lookup-cap [sys agent-key cap-key]
  (let [agent (get (:agents sys) agent-key)]
    (when-not agent
      (throw (ex-info "Agent not found in system"
                      {:agent agent-key :agents (keys (:agents sys))})))
    (let [cap-config (get-in agent [:compiled :caps cap-key])]
      (when-not cap-config
        (throw (ex-info "Cap not found"
                        {:agent agent-key
                         :cap   cap-key
                         :caps  (keys (get-in agent [:compiled :caps]))})))
      {:agent agent :cap cap-config})))

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
  (let [{:keys [agent cap]} (lookup-cap sys agent-key cap-key)]
    (when (:input cap)
      (validate-schema! (:input cap) input :input))
    (-> (executor/execute agent (:store sys) input
                          {:middleware (:middleware sys)
                           :agent      agent-key
                           :cap        cap-key
                           :entry      (:entry cap)
                           :resolver   (:resolver @(:state sys))})
        (wrap-output-validation (:output cap)))))
