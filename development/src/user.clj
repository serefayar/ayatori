(ns user
  (:require
   [reloaded.repl :as r :refer [system init start stop go]]
   [malli.dev :as dev]
   [malli.dev.pretty :as pretty]
   [ayatori.rest-api.main :as m]))

(reloaded.repl/set-init! #(m/new-system (m/config)))

(defn reset
  []
  (dev/stop!)
  (r/reset)
  (dev/start! {:report (pretty/reporter (pretty/-printer {:width 80}))}))

