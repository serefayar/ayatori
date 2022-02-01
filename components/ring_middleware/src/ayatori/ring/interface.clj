(ns ayatori.ring.interface
  (:require [ayatori.ring.core :as rm]))

(defn lra-handler-sync
  [handler request options]
  (rm/lra-handler-sync handler request options))

(defn lra-handler-async
  [handler request respond raise options]
  (rm/lra-handler-async handler request respond raise options))
