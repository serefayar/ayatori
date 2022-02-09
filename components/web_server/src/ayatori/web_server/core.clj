(ns ayatori.web-server.core
  (:require [com.stuartsierra.component :as component]
            [aleph.http :as http]
            [clojure.tools.logging :as log]))

(defrecord WebServer [handler-fn
                      app-state
                      server]
  component/Lifecycle
  (start [this]
    (if server
      this
      (let [{:keys [host port context-path]} (-> app-state :config :web-server)]
        (log/infof "web server running at %s:%s" host port)
        (assoc this
               :server (http/start-server
                        (http/wrap-ring-async-handler
                         (handler-fn context-path app-state))
                        {:host host :port port})))))
  (stop [this]
    (if server
      (do
        (.close server)
        (assoc this :server nil))
      this)))

(defn make-webserver
  [handler-fn]
  (component/using (map->WebServer {:handler-fn handler-fn})
                   [:app-state]))
