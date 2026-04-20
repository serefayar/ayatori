(ns ayatori.helper
  "Convenience functions for agent creation."
  (:refer-clojure :exclude [agent])
  (:require
   [ayatori.core :as aya]))

(def ^:private provider-defaults
  {:ollama {:base-url "http://localhost:11434"}
   :openai {:api-key-env "OPENAI_API_KEY"}
   :anthropic {:api-key-env "ANTHROPIC_API_KEY"}})

(defn llm
  "Creates LLM client config with defaults. Reads api-key from env for openai/anthropic."
  [provider model & [{:keys [base-url api-key]}]]
  (let [defaults (get provider-defaults provider)]
    (cond-> {:provider provider :model model}
      (or base-url (:base-url defaults))
      (assoc :base-url (or base-url (:base-url defaults)))

      (:api-key-env defaults)
      (assoc :api-key (or api-key (System/getenv (:api-key-env defaults)))))))

(defn sliding-memory
  "Sliding window memory config."
  [max-messages & [{:keys [preserve-system] :or {preserve-system true}}]]
  {:strategies [{:type :sliding-window
                 :max-messages max-messages
                 :preserve-system preserve-system}]})

(defn token-memory
  "Token budget memory config."
  [max-tokens & [{:keys [preserve-system] :or {preserve-system true}}]]
  {:strategies [{:type :token-budget
                 :max-tokens max-tokens
                 :preserve-system preserve-system}]})

(defn llm-node
  "Creates LLM node config. Prompt is the system prompt."
  [client prompt & [opts]]
  (merge {:type :llm :client client :prompt prompt} opts))

(defn with-memory [node memory]
  (assoc node :memory memory))

(defn with-tools [node tools]
  (assoc node :tools tools))

(defn with-max-turns [node n]
  (assoc node :max-turns n))

(defn with-streaming
  "Enables streaming for LLM node."
  [node]
  (assoc node :stream true))

(defn with-response-format
  "Adds JSON schema response format."
  [node schema]
  (assoc node :response-format {:type :json-schema :schema schema}))

(defn agent
  "Creates agent from LLM node. Pass tool-nodes map for tool handlers."
  [llm-node* & [tool-nodes opts]]
  (let [tools (get llm-node* :tools [])
        tool-names (map (comp keyword :name) tools)
        tool-edges (zipmap tool-names tool-names)
        handler-edges (when (seq tool-nodes)
                        (zipmap (keys tool-nodes) (repeat :llm)))
        edges (cond-> {:llm tool-edges}  ;; :done implicit terminal
                handler-edges (merge handler-edges))]
    (aya/make-agent
     (merge {:nodes (merge {:llm llm-node*} tool-nodes)
             :edges edges
             :caps {:main {:entry :llm}}}
            opts))))

(defn system
  "Creates system from agent or agent map."
  [agent-or-agents & [opts]]
  (let [agents (if (and (map? agent-or-agents)
                        (not (contains? agent-or-agents :topology)))
                 agent-or-agents
                 {:main agent-or-agents})]
    (aya/make-system (merge {:agents agents} opts))))

(defn with-wiring! [sys wiring]
  (reset! (:wiring sys) wiring)
  sys)

(defn start! [sys]
  (aya/start! sys))

(defn stop! [sys]
  (aya/stop! sys))

(defn run
  "Runs agent cap. Defaults to :main :main. Returns channel."
  ([sys input] (run sys :main :main input))
  ([sys agent-key cap-key input] (aya/run sys agent-key cap-key input)))
