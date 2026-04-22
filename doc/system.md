# System

System groups named agents with shared wiring.

## Setup

```clojure
(def sys (-> (aya/make-system {:agents {:assistant assistant :reviewer reviewer}
                               :wiring {:reviewer {:llm [:assistant :chat]}}})
             aya/start!))
```

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

## Topology Inspection

```clojure
;; Single agent topology
(aya/describe-topology agent)

;; System-wide topology (all agents + wiring)
(aya/describe-system-topology sys)
;; => {:agents {:assistant {...} :reviewer {...}}
;;     :wiring {:reviewer {:llm [:assistant :chat]}}
;;     :edges [{:from [:reviewer :llm] :to [:assistant :chat]}]
;;     :orphans #{:assistant}}
```

## Lifecycle

- `start!` builds cap-map, resolver, starts agent flows
- `stop!` stops agent flows, clears state

### Pause and Resume

```clojure
;; Pause an agent (messages queue but don't process)
(aya/pause-agent! sys :assistant)

;; Resume processing
(aya/resume-agent! sys :assistant)

;; Health check
(async/<!! (aya/ping-agent sys :assistant))
```

Use cases:
- Hot reload / maintenance
- Rate limiting
- Liveness probes
