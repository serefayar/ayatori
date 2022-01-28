(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [org.corfield.build :as bb]))

(def lib 'ayatori/ayatori)
(def version (format "0.1.%s" (b/git-count-revs nil)))
(def main 'ayatori.rest-api.main)

(defn uber "Build the uberjar." [opts]
  (-> opts
      (assoc :lib lib :version version :main main)
      (bb/clean)
      (bb/uber)))
