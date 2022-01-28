(ns user
  (:require
   [reloaded.repl :refer [system init start stop go reset reset-all]]
   [ayatori.rest-api.main :as m]))

(reloaded.repl/set-init! #(m/new-system (m/config)))
