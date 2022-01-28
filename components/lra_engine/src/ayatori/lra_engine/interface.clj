(ns ayatori.lra-engine.interface
  (:require
   [ayatori.lra-engine.core :as engine]))

(defn close!
  [lra]
  (engine/close! lra))

(defn cancel!
  [lra]
  (engine/cancel! lra))
