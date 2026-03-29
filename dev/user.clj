(ns user
  (:require
   [malli.dev :as md]
   [clojure.tools.namespace.repl :refer [refresh]]
   [malli.dev.pretty :as pretty]))

(alter-var-root #'*warn-on-reflection* (constantly true))

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