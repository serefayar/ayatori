# LLM Node

LLM nodes handle conversations, tool calling, and structured output.

## Configuration

```clojure
{:type :llm
 :client {:provider :ollama
          :model "gpt-oss:20b"
          :base-url "http://localhost:11434"}
 :prompt "You are a helpful assistant."
 :tools [...]
 :response-format {...}
 :memory {...}
 :stream true
 :max-turns 50}
```

## Providers

### Ollama (local)

```clojure
{:provider :ollama
 :model "llama3.2"
 :base-url "http://localhost:11434"}
```

### OpenAI

```clojure
{:provider :openai
 :model "gpt-4o"
 :api-key "sk-..."}
```

### Anthropic (Claude)

```clojure
{:provider :anthropic
 :model "claude-sonnet-4-20250514"
 :api-key "sk-ant-..."}
```

### Helper Functions

```clojure
(require '[ayatori.helper :as h])

(h/llm :ollama "llama3.2")
(h/llm :openai "gpt-4o")
(h/llm :anthropic "claude-sonnet-4-20250514")
```

Helper functions read API keys from environment variables:
- OpenAI: `OPENAI_API_KEY`
- Anthropic: `ANTHROPIC_API_KEY`

## Tool Calling

```clojure
(def search-tool
  {:name "search"
   :description "Search the order database"
   :schema [:map [:query :string]]
   :handler (fn [{:keys [query]}]
              [{:id 10258 :customer "Jane Doe" :total 219.47}])})

(def assistant
  (aya/make-agent
    {:nodes {:llm {:type :llm
                   :client {:provider :ollama
                            :model "gpt-oss:20b"
                            :base-url "http://localhost:11434"}
                   :prompt "You are a helpful assistant."
                   :tools [search-tool]}
             :search (fn [input] {:result ((:handler search-tool) input)})}
     :edges {:llm {:search :search}  ;; :done implicit
             :search :llm}
     :caps {:chat {:entry :llm}}}))
```

Tool calls route through graph nodes, so middleware observes every step.

## Structured Output

```clojure
{:type :llm
 :response-format {:type :json-schema
                   :schema [:map
                            [:answer :string]
                            [:count :int]]
                   :max-retries 2}}
```

- `:schema` Malli schema, converted to JSON Schema
- `:max-retries` self-healing retries with error feedback

## Streaming

Enable streaming to receive tokens as they arrive:

```clojure
{:type :llm
 :stream true
 :client {...}
 :prompt "..."}
```

Or with helper:

```clojure
(-> (h/llm :ollama "llama3.2")
    (h/llm-node "You are helpful.")
    (h/with-streaming))
```

### Channel Format

Streaming returns a regular channel (not promise-chan) with multiple values:

```clojure
;; Token events (have :type)
{:type :token :delta "Hello"}
{:type :token :delta " world"}
...

;; Final result (no :type, same as non-streaming)
{:role :assistant :content "Hello world"}
```

### Consuming

```clojure
;; Blocking
(let [ch (h/run sys {:content "Hi"})]
  (loop []
    (when-let [msg (async/<!! ch)]
      (if (:type msg)
        (do (print (:delta msg)) (flush) (recur))
        msg))))  ;; returns final result

;; Non-blocking (go-loop)
(let [ch (h/run sys {:content "Hi"})]
  (async/go-loop []
    (when-let [msg (async/<! ch)]
      (if (:type msg)
        (do (print (:delta msg)) (flush) (recur))
        (println "Done:" msg)))))
```

### With Tool Calls

Streaming works with tool calling. Tokens stream until a tool call arrives, then the tool executes, and streaming resumes:

```clojure
;; Stream: "Let me " -> tool call -> tool result -> "search for that."
;; Final: {:role :assistant :content "Let me search for that."}
```

## Memory

See [Memory](memory.md) for conversation memory configuration.

```clojure
{:type :llm
 :memory {:strategies [{:type :sliding-window
                        :max-messages 20
                        :preserve-system true}]}}
```
