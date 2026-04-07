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
             :search (fn [input _] {:result ((:handler search-tool) input)})}
     :edges {:llm {:done :ayatori/done
                   :search :search}
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

```clojure
{:type :llm
 :stream true
 ...}
```

Returns a channel of tokens.

## Memory

See [Memory](memory.md) for conversation memory configuration.

```clojure
{:type :llm
 :memory {:strategies [{:type :sliding-window
                        :max-messages 20
                        :preserve-system true}]}}
```
