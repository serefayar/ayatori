(ns ayatori.reitit.ring.middleware.ayatori
  (:require [reitit.core :as r]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [jsonista.core :as j]
            [clj-http.client :as client]
            [clojure.tools.logging :as log])
  (:gen-class))

(s/def ::coordinator-url (complement string/blank?))
(s/def ::options (s/keys :req-un [::coordinator-url]))

(s/def ::type #{:required-new
                :required #_notimplented
                :mandatory
                :compansate
                :complete})
(s/def ::end? boolean?)
(s/def ::id keyword?)
(s/def ::lra (s/keys :req-un [::id ::type]
                     :opt-un [::end?]))

;; TODO: duplicated definition
(def context-headers
  {"long-running-action" :code
   "long-running-action-ended" :ended ;; not implemented
   "long-running-action-parent" :parent ;; not implemented
   "long-running-action-recovery" :recovery ;; not implemented
   })

(defn find-lra-defs
  [router lra-id]
  (->> router
       (r/compiled-routes)
       (filter #(= lra-id (-> % second :lra :id)))
       (map (fn [[p a _]] (assoc (:lra a) :route p)))))

(defn base-uri
  [request]
  (format "%s://%s:%s" (name (:scheme request)) (:server-name request) (:server-port request)))

(defn create-acts
  [{:keys [base-uri current-lra lra-defs]}]
  (->> lra-defs
       (drop-while #(= (:type current-lra) (:type %)))
       (map (fn [lra-def] {:act/type (:type lra-def)
                           :act/url (format "%s%s" base-uri (:route lra-def))}))))

(defn new-lra
  [lra-context]
  {:lra/client-id "aaa"
   :lra/time-limit 0
   :lra/parent-code ""
   :lra/acts (create-acts lra-context)})

(defn new-participant
  [lra-context]
  {:participant/client-id "bbb"
   :participant/acts (create-acts lra-context)})

(defn register-request!
  [coordinator-url lra]
  (->> {:body (j/write-value-as-string lra)
        :content-type :json
        :socket-timeout 1000
        :connection-timeout 1000}
       (client/post (format "%s/start" coordinator-url))
       :body))

(defn join-request!
  [coordinator-url code participant]
  (->> {:body (j/write-value-as-string participant)
        :content-type :json
        :socket-timeout 1000
        :connection-timeout 1000}
       (client/put (format "%s/%s" coordinator-url code))
       :body))

(defn close-request!
  [coordinator-url code]
  (->> {:content-type :json
        :socket-timeout 1000
        :connection-timeout 1000}
       (client/put (format "%s/%s/close" coordinator-url code))
       :body))

(defn add-lra-params
  [request lra-context header-params]
  (assoc request
         :lra-headers header-params
         :lra-params (into lra-context (for [[k v] header-params] [(get context-headers k) v]))))

(defn collect-lra-headers
  [request]
  (-> (:headers request)
      (select-keys (map string/lower-case (keys context-headers)))))

(defn header-lra-params
  [request lra-context]
  (->> (collect-lra-headers request)
       (add-lra-params request lra-context)))

(defn start!
  [request {:keys [coordinator-url] :as lra-context}]
  (->> (new-lra lra-context)
       (register-request! coordinator-url)
       (#(add-lra-params request lra-context {"long-running-action" %}))))

(defn join!
  [request {:keys [coordinator-url code] :as lra-context}]
  (->> (new-participant lra-context)
       (#(join-request! coordinator-url code %))
       (#(add-lra-params request lra-context {"long-running-action" %}))))

(defn close!
  [{:keys [coordinator-url code]}]
  (close-request! coordinator-url code))

(defn lra-request-handler
  [request {:keys [current-lra] :as lra-context}]
  (condp = (:type current-lra)
    :required-new (start! request lra-context)
    :mandatory (if (string/blank? (:code lra-context))
                 (throw (ex-info "mandatory" {:type :mandatory-context}))
                       ;; else
                 (join! request lra-context))
    ;; otherwise
    (header-lra-params request lra-context)))

(defn lra-handler
  [request {:keys [coordinator-url]}]
  (if-let [current-lra (-> request :reitit.core/match :data :lra)]
    (if (s/valid? ::lra current-lra)
      (let [request' (header-lra-params request current-lra)
            router (-> request' :reitit.core/router)
            lra-context {:current-lra current-lra
                         :code (-> request' :lra-params :code)
                         :coordinator-url coordinator-url
                         :base-uri (base-uri request)
                         :router router
                         :lra-defs (find-lra-defs router (:id current-lra))}]
        (lra-request-handler request' lra-context))
      ;; else
      (throw (ex-info "invalid lra defination" {:type :spec :data (s/explain-data ::lra current-lra)})))
    ;; else
    request))

(defn lra-response-handler
  [{:keys [current-lra] :as lra-context}]
  (condp = (:type current-lra)
    :required-new (do
                    (log/infof "closing lra %s" (:code lra-context))
                    (close! lra-context))
    ;; TODO: implement other types
    nil))

(defn response-error
  [ex]
  (condp = (-> ex ex-data :type)
    :mandatory-context {:status 412}
    {:status 400}))

(defn wrap-lra
  [handler options]
  {:pre [(s/valid? ::options options)]}
  (fn
    ([request]
     (handler request))

    ([request respond raise]
     (if (seq (-> request :reitit.core/match :data :lra)) ;; in lra context
       (let [[request' ex] (try [(lra-handler request options)]
                                (catch Throwable ex
                                  [nil ex]))]
         (if ex ;; pre-condition
           (respond (response-error ex))
           ;; all ok
           (handler request'
                    (fn [response]
                      (respond response)
                      (future (lra-response-handler (:lra-params request'))))
                    raise)))

       ;; else not in lra context
       (handler request respond raise)))))

(def create-lra-middleware
  {:name ::lra
   :wrap wrap-lra})
