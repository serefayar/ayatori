# Middleware

Middleware hooks into graph execution for observability and control.

## IGraphMiddleware Protocol

```clojure
(defprotocol IGraphMiddleware
  (on-graph-start [this ctx])
  (on-node-start [this ctx])
  (on-node-end [this ctx])
  (on-graph-end [this ctx])
  (on-graph-error [this ctx]))
```

## Context Map

Context includes execution metadata:

- `:exec-id` unique execution ID
- `:agent` agent name
- `:trace-id` distributed trace ID
- `:span-id` current span ID
- `:parent-span-id` parent span ID
- `:path` agent call path

Event-specific keys:

- `:input` (node-start)
- `:result` (node-end, graph-end)
- `:error` (graph-error)
- `:duration-ms` (node-end)
- `:timestamp`

Trace context propagates across agent boundaries.

## Built-in Middleware

### Tap

Emits all events via `tap>`:

```clojure
(require '[ayatori.middleware :as mw])

(def sys (-> (aya/make-system {:agents {...}
                               :middleware [(mw/make-tap)]})
             aya/start!))
```

Use with `add-tap` to capture events:

```clojure
(add-tap println)
```

## Custom Middleware

```clojure
(defrecord LoggingMiddleware []
  mw/IGraphMiddleware
  (on-graph-start [_ ctx] (println "Start:" (:agent ctx)))
  (on-node-start [_ ctx] nil)
  (on-node-end [_ ctx] nil)
  (on-graph-end [_ ctx] (println "End:" (:agent ctx)))
  (on-graph-error [_ ctx] (println "Error:" (:error ctx))))

(def sys (-> (aya/make-system {:agents {...}
                               :middleware [(->LoggingMiddleware)]})
             aya/start!))
```
