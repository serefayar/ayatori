(ns ayatori.web-server.interface
  (:require [ayatori.web-server.core :as web-server]))

(defn create
  [handler-fn]
  (web-server/make-webserver handler-fn))
