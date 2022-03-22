(ns user
  (:require
   [reloaded.repl :as r]
   [clojure.tools.namespace.repl :refer [refresh]]
   [malli.dev :as dev]
   [malli.dev.pretty :as pretty]
   [ayatori.rest-api.main :as m]))

(reloaded.repl/set-init! #(m/new-system (m/config)))

(defn suspend
  []
  (dev/stop!)
  (r/suspend))


#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn resume
  []
  (r/resume)
  (dev/start! {:report (pretty/reporter (pretty/-printer {:width 80}))}))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn reset
  []
  (suspend)
  (refresh :after 'user/resume))
