# Memory

Memory manages conversation history for LLM nodes using a strategy pattern.

## IMemory Protocol

```clojure
(defprotocol IMemory
  (add-message [this msg])
  (get-messages [this])
  (clear! [this]))
```

## Configuration

```clojure
(require '[ayatori.memory :as mem])

;; Default: sliding window with 50 messages, system preserved
(mem/make-memory)

;; Custom configuration
(mem/make-memory {:strategies [{:type :sliding-window
                                :max-messages 20
                                :preserve-system true}]})
```

## Strategies

Each strategy is a map with `:type` and strategy-specific options.

### Sliding Window

Keeps the last N messages. With `:preserve-system true`, system role messages are always kept.

```clojure
{:type :sliding-window
 :max-messages 20
 :preserve-system true}
```

## LLM Node Integration

Pass memory config to LLM node:

```clojure
{:type :llm
 :client {...}
 :prompt "You are helpful."
 :memory {:strategies [{:type :sliding-window
                        :max-messages 30
                        :preserve-system true}]}}
```

## Default Behavior

When no memory config is provided, LLM nodes use:

```clojure
[{:type :sliding-window :max-messages 50 :preserve-system true}]
```
