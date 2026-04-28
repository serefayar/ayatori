# Nodes

Nodes are functions or special types that process data in the graph.

## Node Types

| Type | Definition | Description |
|------|-----------|-------------|
| Function | `(fn [input] -> output)` | Transform data |
| Function (with schema) | `{:fn ... :input ... :output ...}` | Transform with validation |
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

### Node Schema

Function nodes support optional input/output schemas (Malli):

```clojure
:nodes {:process {:fn (fn [input] {:result {:doubled (* 2 (:n input))}})
                  :input [:map [:n :int]]
                  :output [:map [:doubled :int]]}}
```

Caps inherit schema from their entry node:

```clojure
;; Cap inherits :process schema
:caps {:main {:entry :process}}
```

Cap can override node schema:

```clojure
;; Override input, keep output from node
:caps {:main {:entry :process
              :input [:map [:value :int]]}}
```

Validation happens at cap boundaries: input validated on entry, output validated on return.

### Compile-time Schema Validation

`make-agent` validates schema compatibility across edges. If a source node's output doesn't provide all keys required by the target node's input, an error is thrown at graph creation time:

```clojure
;; This fails at make-agent (compile time)
(aya/make-agent
  {:nodes {:a {:fn (fn [_] {:result {:x 1}})
               :output [:map [:x :int]]}
           :b {:fn (fn [_] {:result {}})
               :input [:map [:y :string]]}}  ;; ERROR: :a output missing :y
   :edges {:a :b}
   :caps {:main {:entry :a}}})
;; => ExceptionInfo: Edge schema incompatibility

;; This works: output is superset of input
(aya/make-agent
  {:nodes {:a {:fn (fn [_] {:result {:x 1 :y "hi"}})
               :output [:map [:x :int] [:y :string]]}
           :b {:fn (fn [input] {:result input})
               :input [:map [:x :int]]}}  ;; OK: :a provides :x
   :edges {:a :b}
   :caps {:main {:entry :a}}})
```

Schema validation rules:
- If either node has no schema, validation passes (opt-in)
- Output must provide all keys that input requires
- Extra keys in output are allowed (superset)

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

Edges define routing between nodes. Two formats: keyword (unconditional) or vector of routes (conditional/LLM).

### Route Format

| Format | Meaning | Usage |
|--------|---------|-------|
| `:target` | Unconditional | Edge value |
| `[:target]` | Default route | Last element in conditional |
| `[:target pred]` | Conditional route | Predicate-based routing |
| `[:label :target]` | Named route | LLM tool routing |

### Unconditional

Single target, always routes there:

```clojure
:edges {:parse :enrich, :enrich :score}
```

### Conditional (Function Nodes)

Routing logic lives in the edge definition, not the node. Node returns data, predicates decide the route. Evaluation is sequential, first match wins.

```clojure
:edges {:score [[:approve #(> (:confidence %) 0.8)]
                [:reject  #(> (:confidence %) 0.5)]
                [:fallback]]}   ;; default (no predicate)

;; Node just returns data (no :route key needed)
(defn score [input]
  {:result {:confidence (calculate-confidence input)
            :details input}})
```

Multiple routes with different thresholds:

```clojure
:edges {:classify [[:premium  #(> (:score %) 90)]
                   [:standard #(> (:score %) 50)]
                   [:basic    #(> (:score %) 20)]
                   [:reject]]}
```

**Note:** If a predicate throws an exception, it propagates as a flow error.

### LLM Tool Routing

LLM nodes use `[:label :target]` format where label is the tool name:

```clojure
:edges {:llm [[:search :search-node]      ;; tool "search" routes here
              [:calculate :calc-node]     ;; tool "calculate" routes here
              [:done :output-node]]}      ;; no tool call routes here

;; Or terminal
:edges {:llm [[:search :search-node]
              [:done :ayatori/done]]}
```

For LLM nodes, `:done` route handles non-tool responses. If edge is omitted entirely, it routes to terminal.

### Fan-out

Fan-out nodes only support unconditional edges. Output is always `{:results {:branch-a ... :branch-b ...}}`:

```clojure
:edges {:fan :aggregate}  ;; collected results go to :aggregate
```

### Terminal Routes

`:ayatori/done` can be used in any edge type to terminate the flow and return the result:

```clojure
;; Conditional with terminal
:edges {:validate [[:ok #(:valid %)]
                   [:ayatori/done]]}  ;; invalid data terminates

;; LLM terminal
:edges {:llm [[:done :ayatori/done]]}
```

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
