(ns user
  (:require
   [clojure.tools.namespace.repl :refer [refresh]]
   [clojure.core.async.flow-monitor :as monitor]
   [malli.dev :as md]
   [malli.dev.pretty :as pretty]))

;;(alter-var-root #'*warn-on-reflection* (constantly true))

(defn flow-from-sys [sys agent-name]
  (get-in @(:agents sys) [agent-name :flow :flow]))

(defn start! []
  (md/start! {:report (pretty/reporter)}))

(defn stop! []
  (md/stop!))

(defn reload! []
  (stop!)
  (let [ret (refresh :after `start!)]
    (if (instance? Throwable ret)
      (throw ret)
      ret)))

(comment

  (def flow-server
    (monitor/start-server {:flow (flow-from-sys sys :order)
                           :port 9876
                           :root [:llm]}))

  (monitor/stop-server flow-server))
