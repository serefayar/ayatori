# Nodes

Nodes are functions or special types that process data in the graph.

## Node Types

| Type | Definition | Description |
|------|-----------|-------------|
| Function | `(fn [input] -> output)` | Transform data |
| LLM | `{:type :llm :client ... :prompt ...}` | Conversation, tools, structured output |
| Fan-out | `{:type :fan-out :branches [...]}` | Parallel execution |

## Function Node

All function nodes have the same signature:

```clojure
(fn [input] -> output)
```

- `input`: data flowing through the graph
- Returns: data to pass to next node

### Return Values

- Any value: passed to next node
- `{:result r}` unwraps to `r`
- `nil` or `{}` passes input unchanged

### REPL Reloadability

Pass vars instead of functions for hot reloading during development:

```clojure
(defn my-transform [input]
  {:result {:processed (:raw input)}})

;; Use var for REPL reloadability
(def agent
  (aya/make-agent
    {:nodes {:transform #'my-transform}  ;; var, not fn
     :edges {}
     :caps {:main {:entry :transform}}}))
```

With vars, redefining the function updates the running flow without restart:

```clojure
;; Redefine the function
(defn my-transform [input]
  {:result {:processed (:raw input) :version 2}})

;; Next call uses new version, no stop!/start! needed
(aya/run sys :agent :main {:raw "data"})
```

## Edge DSL

```clojure
;; Unconditional: keyword
:edges {:parse :enrich, :enrich :score}

;; Conditional: map (route key -> target node)
:edges {:score {:approve :approve-node
                :reject :reject-node}}

;; Node decides the route
(defn score [input]
  {:result (if (> (:confidence input) 0.8)
             {:route :approve :data input}
             {:route :reject :data input})})
```

For LLM nodes, `:done` route is implicit: if not specified in edges, it routes to `:ayatori/done` (terminal). You can also use `:ayatori/done` explicitly as an edge target.

Note: The `:ayatori/*` namespace is reserved for framework use. User-defined nodes and deps cannot use this namespace.

## Fan-out

Parallel execution with branch collection:

```clojure
(def analyzer
  (aya/make-agent
    {:nodes {:analyze {:type :fan-out
                       :branches [:sentiment :toxicity]}
             :sentiment (fn [_] {:result {:score 0.85}})
             :toxicity (fn [_] {:result {:score 0.02}})
             :aggregate (fn [input]
                          {:result {:sentiment (get-in input [:results :sentiment])
                                    :toxicity (get-in input [:results :toxicity])}})}
     :edges {:analyze :aggregate}
     :caps {:main {:entry :analyze}}}))
```
