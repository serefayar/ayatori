# System

System groups named agents with shared middleware, wiring, and state store.

## Setup

```clojure
(def sys (-> (aya/make-system {:agents {:assistant assistant :reviewer reviewer}
                               :middleware [(mw/make-tap)]
                               :wiring {:reviewer {:llm [:assistant :chat]}}
                               :store {:type :edn :path "/tmp/state.edn"}})
             aya/start!))
```

## Store

- Default: in-memory (`:atom`)
- File-based: `{:type :edn :path "..."}`

## Runtime Management

### Add Agents

```clojure
(aya/add-agents! sys {:agents {:caller caller :doubler doubler}
                      :wiring {:caller {:compute [:doubler :main]}}})
```

### Rewire

```clojure
(aya/rewire! sys :caller {:compute [:tripler :main]})
```

### Remove Agents

```clojure
;; No dependents
(aya/remove-agent! sys :lonely-agent)

;; With dependents: must provide rewire plan
(aya/remove-agent! sys :doubler :rewire {:caller {:compute [:tripler :main]}})
```

### Find Orphans

```clojure
(aya/orphans sys)
;; => #{:old-doubler}
```

## Lifecycle

- `start!` builds cap-map, resolver, runs `:on-start` hooks
- `stop!` runs `:on-stop` hooks, clears state
