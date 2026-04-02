# Ayatori
[![Run tests](https://github.com/serefayar/ayatori/actions/workflows/test.yml/badge.svg)](https://github.com/serefayar/ayatori/actions/workflows/test.yml)

<img src="./resources/ayatori.png" align="right" height="150" />

> \
> あやとり
>
> The name comes from ayatori, the Japanese string figure game known in English as cat's cradle.

A graph-based AI agent orchestration engine built in Clojure. Nodes are functions, edges define routing, the executor handles the rest.

See the [introductory article](https://serefayar.substack.com/p/ayatori-agent-orchestration-engine-in-clojure).

> [!WARNING]
> **Status:** Proof of concept, under active development. Not production-ready.

---

## How it works

```
deps -> agent(nodes, edges) -> caps
```

1. **Agent** validates topology and binds node implementations (`make-agent`)
2. **System** groups named agents with shared middleware, wiring, and state store (`make-system`, `start!`/`stop!`)
3. **Run** executes a cap through a system, returns a core.async channel (`run`)

Nodes are functions with signature `(fn [input state] -> {:result ... :state ...})`. Special node types: `:llm`, `:fan-out`. Routing is data: edges are keywords (unconditional) or maps (conditional, based on `:route` key). Agents expose **caps** (capabilities) and declare **deps** (dependencies). The system wires deps to caps at start time.

---

## Quick Start

### Streaming LLM response

```clojure
(require '[ayatori.core :as aya]
         '[clojure.core.async :as async])

(def assistant
  (aya/make-agent
    {:nodes {:llm {:type :llm
                   :stream true
                   :client {:provider :ollama
                            :model "gpt-oss:20b"
                            :base-url "http://localhost:11434"}
                   :prompt "You are a helpful assistant."}}
     :edges {:llm {:done :ayatori/done}}
     :caps {:chat {:entry :llm}}}))

(def sys (-> (aya/make-system {:agents {:assistant assistant}})
             aya/start!))

(let [ch (async/<!! (aya/run sys :assistant :chat {:content "Explain Clojure in 2 sentences."}))]
  (loop []
    (when-let [token (async/<!! ch)]
      (print token)
      (flush)
      (recur)))
  (println))
;; Clojure is a modern, functional Lisp dialect that runs on the JVM ...

(aya/stop! sys)
```

`:stream true` returns a channel of tokens instead of waiting for the full response.

### LLM agent with tool calling and structured output

```clojure
(require '[ayatori.core :as aya]
         '[clojure.core.async :as async]
         '[ayatori.middleware :as mw])

(def search-tool
  {:name "search"
   :description "Search the order database"
   :schema [:map [:query :string]]
   :handler (fn [{:keys [query]}]
              [{:id 10258 :date "2026-03-20" :customer "Jane Doe" :total 219.47}
               {:id 10257 :date "2026-03-19" :customer "John Doe" :total 134.00}
               {:id 10256 :date "2026-03-18" :customer "John Jr." :total 87.39}])})

(def assistant
  (aya/make-agent
    {:nodes {:llm {:type :llm
                   :client {:provider :ollama
                            :model    "gpt-oss:20b"
                            :base-url "http://localhost:11434"}
                   :prompt "You are a helpful assistant."
                   :tools [search-tool]
                   :response-format {:type :json-schema
                                     :schema [:map
                                              [:answer :string]
                                              [:order-count :int]]
                                     :max-retries 2}}
             :search (fn [input _] {:result ((:handler search-tool) input)})}
     :edges {:llm {:done :ayatori/done
                   :search :search}
             :search :llm}
     :caps {:chat {:entry :llm
                   :input [:map [:content :string]]
                   :output [:map [:answer :string] [:order-count :int]]}}}))

(def sys (-> (aya/make-system {:agents {:assistant assistant}
                               :middleware [(mw/make-tap)]})
             aya/start!))

(async/<!! (aya/run sys :assistant :chat {:content "Find recent orders"}))
;; => {:answer "Here are the most recent orders: ..." :order-count 3}

(aya/stop! sys)
```

Tool calls route through graph nodes, so middleware observes every step. `:response-format` enforces output schema (Malli -> JSON Schema) with optional self-healing retries via `:max-retries`.

### Fan-out with parallel analysis

```clojure
(def analyzer
  (aya/make-agent
    {:nodes {:preprocess (fn [input _]
                           {:result {:text (clojure.string/lower-case (:raw input))}})
             :analyze {:type :fan-out
                       :branches [:sentiment :toxicity]}
             :sentiment (fn [_ _] {:result {:score 0.85 :label :positive}})
             :toxicity (fn [_ _] {:result {:score 0.02 :label :safe}})
             :aggregate (fn [input _]
                          {:result {:sentiment (get-in input [:results :sentiment :label])
                                    :toxicity  (get-in input [:results :toxicity :label])}})}
     :edges {:preprocess :analyze
             :analyze :aggregate}
     :caps {:analyze {:entry :preprocess}}}))

(async/<!! (aya/run (-> (aya/make-system {:agents {:a analyzer}}) aya/start!) :a :analyze {:raw "Great product!"}))
;; => {:sentiment :positive, :toxicity :safe}
```

---

## Caps and Deps

Agents expose **caps** and declare **deps**. The system wires deps to caps.

```clojure
(def doubler
  (aya/make-agent
    {:nodes {:dbl (fn [input _] {:result {:doubled (* 2 (:n input))}})}
     :edges {}
     :caps {:main {:entry :dbl}}}))

(def caller
  (aya/make-agent
    {:nodes {:prep (fn [input _] {:result {:n (:v input)}})}
     :edges {:prep :compute}
     :deps [:compute]
     :caps {:main {:entry :prep}}}))

(def sys (-> (aya/make-system
               {:agents {:caller caller :doubler doubler}
                :wiring {:caller {:compute [:doubler :main]}}})
             aya/start!))

(async/<!! (aya/run sys :caller :main {:v 5}))
;; => {:doubled 10}
```

Cap schemas (`:input`/`:output`) accept Malli schemas for validation. `cap/describe` introspects a CapHandle's schema. `rewire!` changes dep targets at runtime.

### Capability URIs

Capabilities are addressed via human-readable URIs:

```
ayatori://host:port/c/{agent-name}/{cap-name}
```

Example: `ayatori://localhost:9000/c/calculator/compute`

URIs are self-describing for debugging and control plane visibility. Security is handled via [kex](https://github.com/serefayar/kex) tokens, not URI obscurity. Deps are resolved at runtime via wiring, enabling hot-swap without restart.

---

## System

```clojure
(def sys (-> (aya/make-system {:agents {:assistant assistant :reviewer reviewer}
                               :middleware [(mw/make-tap)]
                               :wiring {:reviewer {:llm [:assistant :chat]}}
                               :store {:type :edn :path "/tmp/state.edn"}})
             aya/start!))
```

`:store` is optional. Default: in-memory (`:atom`). File-based persistence: `{:type :edn :path "..."}`.

### Runtime Management

Add agents to running systems with `add-agents!`:

```clojure
(aya/add-agents! sys {:agents {:caller caller :doubler doubler}
                      :wiring {:caller {:compute [:doubler :main]}}})
```

Change wiring at runtime with `rewire!`:

```clojure
(aya/rewire! sys :caller {:compute [:tripler :main]})
```

## Middleware

`IGraphMiddleware` protocol hooks: `on-graph-start`, `on-node-start`, `on-node-end`, `on-graph-end`, `on-graph-error`.

Context map includes `:exec-id`, `:agent`, `:trace-id`, `:span-id`, `:parent-span-id`, `:path`, and event-specific keys. Trace context propagates across agent boundaries.

Built-in: `(mw/make-tap)` emits all events via `tap>`.

---

## Node Types

| Type | Definition | Description |
|------|-----------|-------------|
| Function | `(fn [input state] -> {:result ... :state ...})` | Transform data and/or update agent state |
| LLM | `{:type :llm :client ... :prompt ... :tools [...]}` | Conversation, tool routing, structured output. `:stream true` for streaming. |
| Fan-out | `{:type :fan-out :branches [...] :strategy :collect-all}` | Parallel execution. `:collector` for coordinated state updates. |

### Function Node

All function nodes have the same signature:

```clojure
(fn [input state] -> {:result ... :state ...})
```

- `input`: data flowing through the graph
- `state`: agent's persistent memory

### Return Values

- `{:result r}` transform output
- `{:state s}` update agent memory
- `{:result r :state s}` both
- `nil` or `{}` pass input unchanged

### Lifecycle Hooks

Initialize agent state with `:on-start`. The `ctx` parameter contains `{:agent-key :caps :deps}`:

```clojure
(aya/make-agent
  {:nodes {:counter (fn [input state]
                      {:result {:count (:n state)}
                       :state (update state :n inc)})}
   :edges {}
   :caps {:main {:entry :counter}}
   :lifecycle {:on-start (fn [ctx] {:n 0})}})
```

## Edge DSL

```clojure
;; Unconditional: keyword
:edges {:parse :enrich, :enrich :score}

;; Conditional: map (route key -> target node)
:edges {:score {:approve :approve-node
                :reject :reject-node
                :done :ayatori/done}}

;; Node decides the route
(defn score [input _]
  {:result (if (> (:confidence input) 0.8)
             {:route :approve :data input}
             {:route :reject :data input})})
```

`:ayatori/done` ends execution and returns `:data` as the final result.

---

## TODOs

- [ ] Agent management: `remove-agent!`
- [ ] Distributed execution: multi-node transport, capability-aware routing
- [ ] [kex](https://github.com/serefayar/kex) integration: cryptographic capability tokens with attenuation
- [ ] LLM providers: OpenAI, Anthropic, etc. (currently only Ollama)
- [ ] MCP integration: server (expose agents) and client (consume tools)
- [ ] Observability: topology visualization, async middleware dispatch

---

## License

Copyright (c) 2026 Seref R. Ayar.
Distributed under the Eclipse Public License version 1.0.
