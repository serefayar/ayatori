(ns ayatori.rest-api.main
  (:require [com.stuartsierra.component :as component]
            [ayatori.web-server.interface :as web-server]
            [ayatori.app-state.interface :as app-state]
            [ayatori.database.interface :as database]
            [ayatori.rest-api.routes :as routes]
            [aero.core :as aero]
            [clojure.java.io :as io])
  (:gen-class))

(defn new-system
  [config]
  (component/system-map :app-state (app-state/create config)
                        :database (database/create (-> config :database))
                        :web-server (web-server/create #'routes/app-handler)))

(defn config
  []
  (->> (io/resource "rest_api/config.edn")
       (aero/read-config)))

(defn -main
  []
  (->> (config)
       (new-system)
       (component/start)))
