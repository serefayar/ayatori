(ns ayatori.reitit.ring.middleware.ayatori
  (:require [reitit.core :as r]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [jsonista.core :as j]
            [clj-http.client :as client])
  (:gen-class))

(s/def ::coordinator-url (complement string/blank?))
(s/def ::options (s/keys :req-un [::coordinator-url]))

(defn find-lra-defs
  [router lra-id]
  (->> router
       (r/compiled-routes)
       (filter #(= lra-id (-> % second :lra :id)))
       (map (fn [[p a _]] (assoc (:lra a) :route p)))))

(defn base-uri
  [request]
  (format "%s://%s:%s" (name (:scheme request)) (:server-name request) (:server-port request)))

(defn create-lra
  [base-uri current-lra lra-defs]
  {:lra/client-id "aaa"
   :lra/time-limit 0
   :lra/parent-code ""
   :lra/acts (->> lra-defs
                  (drop-while #(= (:type current-lra) (:type %)))
                  (map (fn [lra-def] {:act/type (:type lra-def)
                                      :act/url (format "%s%s" base-uri (:route lra-def))})))})

(defn register-request!
  [coordinator-url lra]
  (->> {:body (j/write-value-as-string lra)
        :content-type :json
        :socket-timeout 1000
        :connection-timeout 1000}
       (client/post (format "%s/start" coordinator-url))
       :body))

(defn add-lra-params
  [request params]
  (assoc request :lra-params params))

(defn header-lra-params
  [request]
  (if-let [lra-code (-> (:headers request)
                        (get "Long-Running-Action"))]
    (add-lra-params request {:code lra-code})
    (throw (ex-info "LRA Header not found for request" {:type :no-lra-code-found
                                                        :url (:uri request)}))))

(defn register-lra
  [request current-lra coordinator-url]
  (let [router (-> request :reitit.core/router)
        base-uri (base-uri request)
        lra-defs (find-lra-defs router (:id current-lra))]

    (->> (create-lra base-uri current-lra lra-defs)
         (register-request! coordinator-url)
         (#(add-lra-params request {:code %})))))

(defn lra-handler
  [request {:keys [coordinator-url] :as options}]
  {:pre [(s/valid? ::options options)]}
  (when-let [current-lra (-> request :reitit.core/match :data :lra)]
    (case (:type current-lra)
      :required-new (register-lra request current-lra coordinator-url)
      ;; TODO: handle other lra types
      (header-lra-params request))))

(defn wrap-lra
  [handler options]
  (fn
    ([request]
     (handler (lra-handler request options)))
    ([request respond raise]
     (handler (lra-handler request options) respond raise))))
