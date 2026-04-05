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

### Token Budget

Removes oldest messages when total token count exceeds budget. Uses a simple character/4 heuristic by default, or accepts a custom count function.

```clojure
{:type :token-budget
 :max-tokens 4000
 :preserve-system true
 :count-fn (fn [msg] (tiktoken-count (:content msg)))}
```

| Option | Default | Description |
|--------|---------|-------------|
| `:max-tokens` | 4000 | Maximum token budget |
| `:preserve-system` | true | Keep system messages regardless of budget |
| `:count-fn` | char/4 | Custom token counting function |

### Summary

Summarizes old messages via LLM when message count exceeds threshold. Keeps recent messages intact.

```clojure
{:type :summary
 :threshold 20
 :keep-recent 5
 :llm {:provider :openai
       :model "gpt-4o-mini"
       :api-key "..."}
 :prompt "Summarize this conversation concisely:"}
```

| Option | Default | Description |
|--------|---------|-------------|
| `:threshold` | 20 | Trigger summarization when message count exceeds this |
| `:keep-recent` | 5 | Number of recent messages to preserve unchanged |
| `:llm` | required | LLM client config for summarization |
| `:prompt` | "Summarize..." | Prompt prefix for summary request |
| `:invoke-fn` | nil | Custom invoke function (for testing) |

The summary is stored as a system message with `[Summary]` prefix. Old summaries are replaced when re-summarizing.

## Chaining Strategies

Multiple strategies can be combined. They execute in order:

```clojure
(mem/make-memory {:strategies [{:type :summary
                                :threshold 30
                                :keep-recent 10
                                :llm {...}}
                               {:type :token-budget
                                :max-tokens 8000}]})
```

`add-message` returns a channel.

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
