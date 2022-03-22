(ns ayatori.rest-api.main
  (:require [com.stuartsierra.component :as component]
            [clojure.core.async :as async]
            [ayatori.web-server.interface :as web-server]
            [ayatori.app-state.interface :as app-state]
            [ayatori.database.interface :as database]
            [ayatori.lra-engine.interface :as lra-engine]
            [ayatori.rest-api.routes :as routes]
            [aero.core :as aero]
            [clojure.java.io :as io])
  (:gen-class))

(defn new-system
  [config]
  (component/system-map :lra-engine-input-chan (async/chan)
                        :database (database/create (-> config :database))
                        :lra-engine (lra-engine/create)
                        :app-state (app-state/create config)
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
