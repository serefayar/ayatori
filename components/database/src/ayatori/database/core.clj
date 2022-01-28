(ns ayatori.database.core
  (:require [com.stuartsierra.component :as component]
            [datascript.core :as d]))

(defrecord Database [schema
                     datasource]
  component/Lifecycle

  (start [this]
    (if datasource
      this
      (assoc this :datasource (d/create-conn schema))))

  (stop [this]
    (assoc this :datasource nil))

  clojure.lang.IFn
  (invoke [_] datasource))

(defn make-database
  [{:keys [schema]}]
  (map->Database {:schema schema}))
