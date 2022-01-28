(ns ayatori.app-state.core
  (:require
   [com.stuartsierra.component :as component]))

(defrecord AppState [config database]
  component/Lifecycle
  (start [this]
    this)
  (stop [this]
    this))

(defn make-appstate
  [config]
  (component/using (map->AppState {:config config})
                   [:database]))
