(ns ayatori.rest-api.response
  (:require
   [exoscale.ex :as ex]
   [ring.util.response :as resp]))

(defmulti error
  (fn [ex]
    (-> ex
        (ex-data)
        ::ex/type
        ((comp keyword name)))))

(defmethod error :default [ex]
  (-> ex
      (ex-message)
      (resp/bad-request)
      (resp/content-type "text/plain")))

(defmethod error :unkown-error [ex]
  (-> ex
      (ex-message)
      (resp/response)
      (resp/status 500)
      (resp/content-type "text/plain")))

(defn ok
  [body]
  (resp/response body))

(defn created
  ([body]
   (created "" body))
  ([url body]
   (resp/created url body)))
