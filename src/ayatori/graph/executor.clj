(ns ayatori.graph.executor
  (:require
   [ayatori.cap :as cap]
   [ayatori.graph.llm :as llm]
   [ayatori.graph.store :as store]
   [ayatori.middleware :as mw]
   [clojure.core.async :as async :refer [<!]]))

(declare execute)

(defn- normalize-fn-result [ret input]
  (cond
    (nil? ret) {:result input}
    (not (map? ret)) {:result ret}
    :else {:result (get ret :result input)
           :state (:state ret)}))

(defn- invoke-branch [nodes branch input agent-state]
  (let [node (get nodes branch)]
    (when-not node
      (throw (ex-info "Fan-out branch node not found"
                      {:branch branch :bound-nodes (keys nodes)})))
    (if (fn? node)
      (let [ret (node input agent-state)]
        (normalize-fn-result ret input))
      (throw (ex-info "Branch node must be a function" {:branch branch})))))

(defn- invoke-fan-out [config input nodes agent-state _opts]
  (let [branches (:branches config)
        strategy (or (:strategy config) :collect-all)
        collector (:collector config)
        chs (mapv (fn [branch]
                    (async/go
                      (try
                        [branch (invoke-branch nodes branch input agent-state)]
                        (catch Throwable e [branch e]))))
                  branches)]
    (async/go
      (let [all (loop [remaining chs, results []]
                  (if (empty? remaining)
                    results
                    (recur (rest remaining)
                           (conj results (<! (first remaining))))))
            collected (if (= strategy :fail-fast)
                        (do (doseq [[_ result] all]
                              (when (instance? Throwable result)
                                (throw result)))
                            {:results (into {} (map (fn [[k v]] [k (:result v)]) all))})
                        (reduce (fn [acc [branch result]]
                                  (if (instance? Throwable result)
                                    (assoc-in acc [:errors branch] result)
                                    (assoc-in acc [:results branch] (:result result))))
                                {:results {} :errors {}}
                                all))]
        (if collector
          (let [collector-node (get nodes collector)]
            (when-not collector-node
              (throw (ex-info "Fan-out collector node not found"
                              {:collector collector :bound-nodes (keys nodes)})))
            (let [ret (collector-node collected agent-state)]
              (normalize-fn-result ret collected)))
          {:result collected :state nil})))))

(defn- wrap-result [result]
  (if (instance? Throwable result)
    result
    {:result result :state nil}))

(defn- invoke-cap-handle [ch input opts]
  (let [resolver (:resolver opts)]
    (when-not resolver
      (throw (ex-info "No resolver for CapHandle (use a system for inter-agent calls)"
                      {:uri (cap/cap-uri ch)})))
    (async/go
      (let [trace-ctx (select-keys opts [:trace-id :span-id :path])
            result    (<! (resolver (cap/cap-uri ch) input trace-ctx))]
        (wrap-result result)))))

(defn- invoke-dep [dep-key input opts]
  (let [{:keys [resolver wiring agent sys-host sys-port]} opts
        [target-agent target-cap] (get-in wiring [agent dep-key])]
    (when-not resolver
      (throw (ex-info "No resolver for dep (use a system for inter-agent calls)"
                      {:dep dep-key})))
    (when-not target-agent
      (throw (ex-info "Unresolved dep: no wiring found"
                      {:agent agent
                       :dep dep-key
                       :available-wiring (keys (get wiring agent))})))
    (let [uri (cap/make-uri sys-host sys-port target-agent target-cap)]
      (async/go
        (let [trace-ctx (select-keys opts [:trace-id :span-id :path])
              result (<! (resolver uri input trace-ctx))]
          (wrap-result result))))))

(defn- invoke-node [node input agent-state nodes opts]
  (cond
    (fn? node)
    (async/go
      (try
        (let [ret (node input agent-state)]
          (normalize-fn-result ret input))
        (catch Throwable e e)))

    (cap/cap-handle? node)
    (invoke-cap-handle node input opts)

    (keyword? node)
    (invoke-dep node input opts)

    (= :llm (:type node))
    (llm/invoke-llm-node node input agent-state)

    (= :fan-out (:type node))
    (invoke-fan-out node input nodes agent-state opts)))

(defn- resolve-route [edges current-node output]
  (let [edge (get edges current-node)]
    (cond
      (nil? edge)
      nil

      (keyword? edge)
      {:next-node edge :input output}

      (map? edge)
      (let [route-key (:route output)]
        (when-not route-key
          (throw (ex-info "Node output missing :route for conditional edge"
                          {:node current-node :output output :expected-routes (keys edge)})))
        (let [target (get edge route-key)]
          (when-not target
            (throw (ex-info "Unknown route from node"
                            {:node         current-node :route route-key
                             :valid-routes (keys edge)})))
          (when-not (= :ayatori/done target)
            {:next-node target :input (or (:data output) output)}))))))

(defn- extract-result [output]
  (if (and (map? output) (contains? output :route))
    (or (:data output) output)
    output))

(defn- emit [middlewares method ctx]
  (doseq [m middlewares]
    (method m ctx)))

(defn- now-ms [] (System/currentTimeMillis))

(defn execute
  "Runs a bound graph with input. Returns a channel of the final result."
  [bound-graph state-store input opts]
  (let [{:keys [compiled nodes]} bound-graph
        {:keys [edges max-steps deps]} compiled
        deps-set (set deps)
        entry (:entry opts)
        exec-id (str (random-uuid))
        middleware (or (:middleware opts) [])
        agent-key (:agent opts)
        agent-state-atom (:agent-state opts)
        trace-id (or (:trace-id opts) (str (random-uuid)))
        span-id (str (random-uuid))
        parent-span (:span-id opts)
        path (conj (or (:path opts) []) agent-key)
        opts (assoc opts
                    :trace-id trace-id
                    :span-id span-id
                    :path path
                    :state-store state-store)
        ctx {:exec-id exec-id
             :agent agent-key
             :trace-id trace-id
             :span-id span-id
             :parent-span-id parent-span
             :path path}]
    (async/go
      (let [graph-start (now-ms)]
        (emit middleware mw/on-graph-start
              (assoc ctx
                     :entry entry
                     :input input
                     :timestamp graph-start))
        (try
          (loop [current-node entry
                 current-input input
                 step-count 0]
            (when (>= step-count max-steps)
              (throw (ex-info "Graph max steps exceeded"
                              {:max-steps max-steps
                               :node current-node
                               :step-count step-count})))
            (let [node-impl (or (get nodes current-node)
                                (when (contains? deps-set current-node)
                                  current-node)
                                (throw (ex-info "No implementation bound for node"
                                                {:node current-node
                                                 :bound-nodes (keys nodes)})))
                  agent-state (when agent-state-atom @agent-state-atom)]
              (store/save-exec! state-store exec-id
                                {:exec-id exec-id
                                 :current-node current-node
                                 :input current-input
                                 :step-count step-count
                                 :status :running})
              (let [node-start (now-ms)
                    _ (emit middleware mw/on-node-start
                            (assoc ctx
                                   :node current-node
                                   :input current-input
                                   :step step-count
                                   :timestamp node-start))
                    node-result (<! (invoke-node node-impl current-input agent-state nodes opts))
                    _ (when (instance? Throwable node-result) (throw node-result))
                    {:keys [result state streaming]} node-result]
                (if streaming
                  (do
                    (store/delete-exec! state-store exec-id)
                    (emit middleware mw/on-graph-end
                          (assoc ctx
                                 :result :streaming
                                 :steps (inc step-count)
                                 :duration-ms (- (now-ms) graph-start)
                                 :timestamp (now-ms)))
                    result)
                  (do
                    (emit middleware mw/on-node-end
                          (assoc ctx
                                 :node current-node
                                 :input current-input
                                 :result result
                                 :duration-ms (- (now-ms) node-start)
                                 :step step-count
                                 :timestamp (now-ms)))
                    (when (and state agent-state-atom)
                      (reset! agent-state-atom state))
                    (if-let [route (resolve-route edges current-node result)]
                      (recur (:next-node route) (:input route) (inc step-count))
                      (let [final (extract-result result)]
                        (store/delete-exec! state-store exec-id)
                        (emit middleware mw/on-graph-end
                              (assoc ctx
                                     :result final
                                     :steps (inc step-count)
                                     :duration-ms (- (now-ms) graph-start)
                                     :timestamp (now-ms)))
                        final)))))))
          (catch Throwable e
            (emit middleware mw/on-graph-error
                  (assoc ctx :error e :timestamp (now-ms)))
            e))))))
