# Agents

Agents are directed graphs of nodes connected by edges. They expose **caps** (capabilities) and declare **deps** (dependencies).

## Caps and Deps

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

- `:caps` exposes entry points with optional `:input`/`:output` schemas (Malli)
- `:deps` declares dependencies resolved via wiring
- `cap/describe` introspects a CapHandle's schema

## Capability URIs

Capabilities are addressed via URIs:

```
ayatori://host:port/c/{agent-name}/{cap-name}
```

Example: `ayatori://localhost:9000/c/calculator/compute`

URIs are self-describing for debugging. Security is handled via [kex](https://github.com/serefayar/kex) tokens, not URI obscurity.

## Wiring

System wires deps to caps at start time:

```clojure
{:wiring {:caller {:compute [:doubler :main]}}}
```

Change wiring at runtime:

```clojure
(aya/rewire! sys :caller {:compute [:tripler :main]})
```
