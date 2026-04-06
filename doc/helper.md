# Helper Functions

Convenience functions for quick agent creation.

```clojure
(require '[ayatori.helper :as h])
```

## LLM Client

```clojure
(h/llm :ollama "llama3:8b")
;; => {:provider :ollama :model "llama3:8b" :base-url "http://localhost:11434"}

(h/llm :openai "gpt-4o")
;; reads OPENAI_API_KEY from env

(h/llm :anthropic "claude-sonnet-4-20250514")
;; reads ANTHROPIC_API_KEY from env

(h/llm :openai "gpt-4o" {:api-key "sk-..."})
;; explicit api-key
```

## LLM Node

```clojure
(h/llm-node client "System prompt")
(h/llm-node client "System prompt" {:max-turns 20})
```

## Memory

```clojure
(h/sliding-memory 50)
(h/sliding-memory 50 {:preserve-system false})

(h/token-memory 4000)
```

## Modifiers

Threading-friendly functions to configure nodes:

```clojure
(-> node
    (h/with-memory (h/sliding-memory 50))
    (h/with-tools [tool1 tool2])
    (h/with-max-turns 30)
    (h/with-response-format [:map [:answer :string]]))
```

## Agent

```clojure
(h/agent llm-node)
;; single LLM agent with :main cap

(h/agent llm-node {:search search-fn})
;; with tool handler nodes
```

## System

```clojure
(h/system agent)
;; single agent system

(h/system {:assistant agent1 :reviewer agent2})
;; multi agent

(h/with-wiring sys {:reviewer {:check [:assistant :main]}})

(h/start! sys)

(h/run sys {:content "Hello"})        ;; defaults to :main :main
(h/run sys :agent :cap {:content "Hello"})
```

## Full Example

```clojure
(require '[ayatori.helper :as h]
         '[ayatori.core :as aya]
         '[clojure.core.async :as async])

(def lookup-tool
  {:name "lookup"
   :description "Look up order by ID"
   :schema [:map [:id :int]]})

(def sys
  (-> (h/llm :ollama "llama3:8b")
      (h/llm-node "You are an order assistant.")
      (h/with-memory (h/sliding-memory 50))
      (h/with-tools [lookup-tool])
      (h/agent {:lookup (fn [{:keys [id]} _]
                          {:result (str "Order " id ": shipped")})})
      (h/system)
      (h/start!)))

(async/<!! (h/run sys {:content "What is the status of order 123?"}))

(h/stop! sys)
```
