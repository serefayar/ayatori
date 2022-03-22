(ns ayatori.database.interface
  (:require [ayatori.database.core :as db])
  (:import (ayatori.database.core Database)))

(def DatabaseComponent
  ;; check an instance of for now
  [:fn (fn [v] (instance? Database v))])


(defn create
  [config]
  (db/make-database config))
