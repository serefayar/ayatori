# Resilience

Timeout, retry, circuit breaker, rate limiting, bulkhead, and fallback for node execution.

## Configuration

```clojure
(aya/make-system
  {:agents {:order order-agent
            :inventory inventory-agent}
   :wiring {:order {:check-stock [:inventory :check]}}
   
   :resilience {:defaults {:timeout-ms 5000}
                :order {:llm {:timeout-ms 30000
                              :retry {:max-retries 2
                                      :backoff-ms [1000 :exponential 2.0 10000]}
                              :circuit-breaker {:failure-threshold 5
                                                :success-threshold 3
                                                :delay-ms 30000}
                              :rate-limit {:rate 10
                                           :period :second}
                              :fallback (fn [input error]
                                          {:content "Service temporarily unavailable"})}
                        :fetch {:bulkhead {:concurrency 5}}}}})
```

## Config Resolution

```
defaults < node specific
```

```clojure
:resilience {:defaults {:timeout-ms 5000}
             :order {:fetch {:timeout-ms 10000}}}

;; :order/fetch -> 10000ms (specific)
;; :order/process -> 5000ms (defaults)
;; :inventory/check -> 5000ms (defaults)
```

## Node Type Compatibility

| Pattern | pure | llm | dep | router | fan-out |
|---------|------|-----|-----|--------|---------|
| timeout | Y | Y | Y | N | N |
| retry | Y | Y | Y | N | N |
| circuit-breaker | Y | Y | Y | N | N |
| rate-limit | Y | Y | Y | N | N |
| bulkhead | Y | Y | Y | N | N |
| fallback | Y | Y | Y | N | N |

**Pure/LLM/Dep**: All patterns allowed (I/O operations).
**Router/Fan-out**: No resilience (internal coordination, no I/O).

Invalid config at `make-system` throws with Malli validation error.

### LLM Streaming

For streaming LLM nodes, resilience applies to:
- Stream initialization (connection, first request)
- Tool call continuations

Resilience does **not** apply to:
- Individual token delivery (mid-stream errors return partial response)
- Stream processing after successful start

For full resilience coverage, use non-streaming mode.

#### Partial Response on Stream Interruption

When a stream is interrupted mid-way (connection lost, server error), a partial response is returned instead of throwing:

```clojure
{:partial? true
 :content "Content received before interruption..."
 :error :stream-interrupted  ;; or :stream-closed
 :error-detail "Connection reset"
 :role :assistant}
```

Caller can decide to retry or accept partial content.

## Execution Order

```
rate-limit -> bulkhead -> circuit-breaker -> timeout -> retry (with fallback)
```

## Timeout

```clojure
:resilience {:order {:fetch {:timeout-ms 5000}}}
```

Throws on timeout:

```clojure
(ex-info "Resilience failure"
         {:ayatori/error-type :timeout
          :agent :order
          :node :fetch
          :pattern :timeout
          :config {:timeout-ms 5000}})
```

## Retry

```clojure
:resilience {:order {:fetch {:retry {:max-retries 3
                                     :backoff-ms [100 :exponential 2.0 5000]}}}}
```

### Options

| Option | Description |
|--------|-------------|
| `:max-retries` | Maximum retry attempts (excludes initial) |
| `:backoff-ms` | Delay strategy between retries |
| `:retry-exceptions` | Exception classes to retry (default: all exceptions) |
| `:retry-callback` | Callback `(fn [exception])` for logging/metrics |

### Backoff Strategies

```clojure
[100 :constant]                    ;; 100, 100, 100...
[100 :exponential 2.0 5000]        ;; 100, 200, 400, 800... max 5000
```

### Selective Retry

```clojure
;; Only retry specific exceptions
:retry {:max-retries 3
        :retry-exceptions [java.net.SocketTimeoutException
                           java.net.ConnectException]}
```

## Circuit Breaker

```clojure
:resilience {:order {:llm {:circuit-breaker {:failure-threshold 5
                                             :success-threshold 3
                                             :delay-ms 30000}}}}
```

| Option | Description |
|--------|-------------|
| `:failure-threshold` | Opens after this many failures |
| `:success-threshold` | Closes after this many successes in half-open |
| `:delay-ms` | Wait before transitioning to half-open |

States: closed -> open -> half-open -> closed

Throws when circuit is open:

```clojure
(ex-info "Resilience failure"
         {:ayatori/error-type :circuit-open
          :agent :order
          :node :llm
          :pattern :circuit-breaker
          :config {:failure-threshold 5 :delay-ms 30000}})
```

## Rate Limit

```clojure
:resilience {:order {:llm {:rate-limit {:rate 10
                                        :period :second}}}}
```

| Option | Description |
|--------|-------------|
| `:rate` | Permits per period |
| `:period` | `:second`, `:minute`, or `:hour` (default: `:second`) |

Blocks until permit is available.

## Bulkhead

```clojure
:resilience {:order {:fetch {:bulkhead {:concurrency 5}}}}
```

| Option | Description |
|--------|-------------|
| `:concurrency` | Maximum concurrent executions |

Blocks until a slot is available.

## Fallback

```clojure
:resilience {:order {:llm {:fallback (fn [input error]
                                       {:content "Default response"})}}}
```

Called when:
- All retries exhausted
- Circuit breaker open
- Any exception (when no retry configured)

## Combined

```clojure
:resilience {:order {:llm {:timeout-ms 5000
                           :retry {:max-retries 3
                                   :backoff-ms [100 :exponential 2.0 2000]}
                           :circuit-breaker {:failure-threshold 5
                                             :delay-ms 30000}
                           :rate-limit {:rate 10 :period :second}
                           :fallback (fn [_ _] {:content "Unavailable"})}}}
```

Max execution time: `(retries + 1) * timeout + backoff delays`

## Topology Visibility

Resilience wrappers appear in topology inspection after `start!`:

```clojure
(aya/describe-system-topology sys)
;; => {:agents {:order {:procs {:ayatori.res/retry+timeout:llm {...}
;;                              :llm {...}}
;;                      :conns [[[:entry :out] [:ayatori.res/retry+timeout:llm :in]]
;;                              [[:ayatori.res/retry+timeout:llm :out] [:llm :in]]
;;                              ...]}}}
```

Wrapper nodes use `:ayatori.res/` namespace prefix. The naming convention is `pattern1+pattern2:node-key` (patterns sorted alphabetically).

Wrapper proc spec contains:

```clojure
{:ins {:in "input"}
 :outs {:out "output"}
 :workload :io
 :wrapper {:target :llm
           :patterns #{:timeout :retry}
           :config {:timeout-ms 5000 :retry {...}}}}
```

## Subgraph Retry Pattern

For retrying multiple nodes together, use composite agent pattern:

```clojure
;; Instead of retrying individual nodes, extract subgraph as separate agent
(def pricing-agent
  (aya/make-agent
    {:nodes {:fetch-price fetch-price-fn
             :check-inventory check-inventory-fn}
     :edges {:fetch-price :check-inventory}
     :caps {:price {:entry :fetch-price}}}))

;; Main agent calls pricing as dep with resilience
(def order-agent
  (aya/make-agent
    {:nodes {:validate validate-fn
             :create-order create-order-fn}
     :edges {:validate :pricing
             :pricing :create-order}
     :deps [:pricing]}))

;; System wires and applies resilience to entire pricing flow
(aya/make-system
  {:agents {:order order-agent
            :pricing pricing-agent}
   :wiring {:order {:pricing [:pricing :price]}}
   :resilience {:order {:pricing {:retry {:max-retries 2}}}}})
```

This retries the entire pricing subgraph (fetch-price + check-inventory) as a unit.

---

> **POC Note:** Current API is config-driven at system level. Future versions may add agent-level transform API (`with-timeout`, `with-retry`) for intrinsic resilience that travels with the agent across systems.
