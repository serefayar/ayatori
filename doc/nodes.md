# Nodes

Nodes are functions or special types that process data in the graph.

## Node Types

| Type | Definition | Description |
|------|-----------|-------------|
| Function | `(fn [input state] -> {:result ... :state ...})` | Transform data, update state |
| LLM | `{:type :llm :client ... :prompt ...}` | Conversation, tools, structured output |
| Fan-out | `{:type :fan-out :branches [...]}` | Parallel execution |

## Function Node

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

## Lifecycle Hooks

```clojure
(aya/make-agent
  {:nodes {:worker (fn [input state] {:result input})}
   :edges {}
   :caps {:main {:entry :worker}}
   :lifecycle {:on-start (fn [ctx] {:n 0})
               :on-stop (fn [ctx state] (println "cleanup"))}})
```

- `:on-start` `(fn [ctx] -> state)`: Runs on `start!` or `add-agents!`. Returns initial state.
- `:on-stop` `(fn [ctx state] -> any)`: Runs on `stop!` or `remove-agent!`. Return value ignored.

## Fan-out

Parallel execution with branch collection:

```clojure
(def analyzer
  (aya/make-agent
    {:nodes {:analyze {:type :fan-out
                       :branches [:sentiment :toxicity]}
             :sentiment (fn [_ _] {:result {:score 0.85}})
             :toxicity (fn [_ _] {:result {:score 0.02}})
             :aggregate (fn [input _]
                          {:result {:sentiment (get-in input [:results :sentiment])
                                    :toxicity (get-in input [:results :toxicity])}})}
     :edges {:analyze :aggregate}
     :caps {:main {:entry :analyze}}}))
```
