(ns ayatori.app-state.interface
  (:require [ayatori.app-state.core :as app-state]))

(defn create
  [config]
  (app-state/make-appstate config))
