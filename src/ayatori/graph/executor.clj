(ns ayatori.graph.executor
  (:require
   [ayatori.cap :as cap]
   [ayatori.graph.llm :as llm]
   [ayatori.graph.store :as store]
   [ayatori.middleware :as mw]
   [clojure.core.async :as async :refer [<!]]))

(declare execute)

(defn- invoke-branch [nodes branch input]
  (let [node (get nodes branch)]
    (when-not node
      (throw (ex-info "Fan-out branch node not found"
                      {:branch branch :bound-nodes (keys nodes)})))
    (if (fn? node)
      (node input)
      ((:handler node) input (:init-state node)))))

(defn- invoke-fan-out [config input nodes]
  (let [branches (:branches config)
        strategy (or (:strategy config) :collect-all)
        chs (mapv (fn [branch]
                    (async/go
                      (try
                        [branch (invoke-branch nodes branch input)]
                        (catch Throwable e [branch e]))))
                  branches)]
    (async/go
      (let [all (loop [remaining chs, results []]
                  (if (empty? remaining)
                    results
                    (recur (rest remaining)
                           (conj results (<! (first remaining))))))]
        (if (= strategy :fail-fast)
          (do (doseq [[_ result] all]
                (when (instance? Throwable result)
                  (throw result)))
              {:result {:results (into {} all)} :state nil})
          {:result (reduce (fn [acc [branch result]]
                             (if (instance? Throwable result)
                               (assoc-in acc [:errors branch] result)
                               (assoc-in acc [:results branch] result)))
                           {:results {} :errors {}}
                           all)
           :state  nil})))))

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
  (let [resolver (:resolver opts)
        wiring (:wiring opts)
        caller-agent (:agent opts)
        sys-host (:sys-host opts)
        sys-port (:sys-port opts)
        [target-agent target-cap] (get-in wiring [caller-agent dep-key])]
    (when-not resolver
      (throw (ex-info "No resolver for dep (use a system for inter-agent calls)"
                      {:dep dep-key})))
    (when-not target-agent
      (throw (ex-info "Unresolved dep: no wiring found"
                      {:agent caller-agent
                       :dep dep-key
                       :available-wiring (keys (get wiring caller-agent))})))
    (let [uri (cap/make-uri sys-host sys-port target-agent target-cap)]
      (async/go
        (let [trace-ctx (select-keys opts [:trace-id :span-id :path])
              result (<! (resolver uri input trace-ctx))]
          (wrap-result result))))))

(defn- invoke-agent-node [config input opts]
  (let [graph (:agent config)
        cap   (get-in graph [:compiled :caps (:cap config)])
        entry (:entry cap)
        agent-name (keyword (str (name (:agent opts)) ">" (name (:cap config))))]
    (async/go
      (let [result (<! (execute graph (:state-store opts) input
                                (assoc opts :entry entry :agent agent-name)))]
        (wrap-result result)))))

(defn- invoke-node [node input node-state nodes opts]
  (cond
    (fn? node)
    (async/go
      (try
        {:result (node input) :state nil}
        (catch Throwable e e)))

    (cap/cap-handle? node)
    (invoke-cap-handle node input opts)

    (keyword? node)
    (invoke-dep node input opts)

    (= :agent (:type node))
    (invoke-agent-node node input opts)

    (= :llm (:type node))
    (llm/invoke-llm-node node input node-state)

    (= :fan-out (:type node))
    (invoke-fan-out node input nodes)

    (= :stateful (:type node))
    (async/go
      (try
        (let [handler (:handler node)
              state (or node-state (:init-state node))]
          (handler input state))
        (catch Throwable e e)))))

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
            (let [node-impl (cond
                              (get nodes current-node)
                              (get nodes current-node)

                              (contains? deps-set current-node)
                              current-node

                              :else
                              (throw (ex-info "No implementation bound for node"
                                              {:node current-node
                                               :bound-nodes (keys nodes)})))
                  node-state (store/get-node-state state-store exec-id current-node)]
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
                    node-result (<! (invoke-node node-impl current-input node-state nodes opts))
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
                    (when state
                      (store/save-node-state! state-store exec-id current-node state))
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
