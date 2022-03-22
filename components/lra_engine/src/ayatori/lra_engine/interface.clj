(ns ayatori.lra-engine.interface
  (:require
   [ayatori.lra-engine.core :as engine]))

(defn create
  []
  (engine/make-lraengine))
