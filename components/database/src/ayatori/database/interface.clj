(ns ayatori.database.interface
  (:require [ayatori.database.core :as db]))

(defn create
  [config]
  (db/make-database config))
