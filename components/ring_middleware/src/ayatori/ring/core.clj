(ns ayatori.ring.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [jsonista.core :as j]
   [clj-http.client :as client]
   [clojure.tools.logging :as log]))

(s/def ::coordinator-url (complement string/blank?))

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(s/def ::options (s/keys :req-un [::coordinator-url]))

(s/def ::type #{:requires-new
                :required #_notimplented
                :mandatory
                :compensate
                :complete})
(s/def ::end? boolean?)
(s/def ::id keyword?)
(s/def ::cancel-on (s/* int?))
(s/def ::cancel-on-family #{:info
                            :success
                            :redirect
                            :client-error
                            :server-error})

(s/def ::client-id string?)

(s/def ::lra-def (s/keys :req-un [::id ::type]
                         :opt-un [::end? ;; not implemented
                                  ::cancel-on ;; not implemented
                                  ::cancel-on-family ;; not implemented
                                  ::client-id]))

(s/def :act/type #{:compensate :complete :status})
(s/def :act/url string?)
(s/def ::act (s/keys :req [:act/type
                           :act/url]))

(s/def :lra/client-id string?)
(s/def :lra/time-limit nat-int?)
(s/def :lra/parent-code string?)
(s/def :lra/acts (s/coll-of ::act :into []))

(s/def ::lra (s/keys :req [:lra/client-id
                           :lra/time-limit
                           :lra/parent-code
                           :lra/acts]))

(s/def :participant/client-id string?)
(s/def :participant/acts (s/coll-of ::act :into []))
(s/def ::participant (s/keys :req [:participant/client-id
                                   :participant/acts]))

(s/def ::body string?)
(s/def ::content-type #{:json})
(s/def ::socket-timeout nat-int?)
(s/def ::connection-timeout nat-int?)
(s/def ::request (s/keys :req-un [::content-type
                                  ::socket-timeout
                                  ::connection-timeout]
                         :opt-un [::body]))

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
       (filter #(= lra-id (-> % second :lra :id)))
       (map (fn [[p a _]] (assoc (:lra a) :route p)))))

(defn base-url
  [{:keys [scheme server-name server-port]
    :or {scheme :http server-name "localhost" server-port "3000"}}]
  (-> (format "%s://%s:%s" ((comp str name) scheme) server-name server-port)
      (java.net.URL.)
      str))

(defn create-acts
  [{:keys [base-uri current-lra lra-defs]}]
  (when current-lra
    (->> lra-defs
         (drop-while #(= (:type current-lra) (:type %)))
         (map (fn [lra-def] {:act/type (:type lra-def)
                             :act/url (format "%s%s" base-uri (:route lra-def))})))))

(defn new-lra
  [{:keys [current-lra] :as lra-context}]
  (when lra-context
    {:lra/client-id   (:id current-lra)
     :lra/time-limit  0 ;; not implemented
     :lra/parent-code (:code lra-context)
     :lra/acts        (create-acts lra-context)}))

(defn new-participant
  [{:keys [current-lra] :as lra-context}]
  (when lra-context
    {:participant/client-id (:id current-lra)
     :participant/acts (create-acts lra-context)}))

(defn new-request
  ([]
   (new-request nil))
  ([body]
   (cond-> {}
     (some? body) (assoc :body (j/write-value-as-string body))
     :always (merge {:content-type :json
                     :socket-timeout 1000
                     :connection-timeout 1000}))))

(defn register-request!
  [coordinator-url lra]
  (->> (new-request lra)
       (client/post (format "%s/start" coordinator-url))
       :body))

(defn join-request!
  [coordinator-url code participant]
  (->> (new-request participant)
       (client/put (format "%s/%s" coordinator-url code))
       :body))

(defn close-request!
  [coordinator-url code]
  (->> (new-request)
       (client/put (format "%s/%s/close" coordinator-url code))
       :body))

(defn cancel-request!
  [coordinator-url code]
  (->> (new-request)
       (client/put (format "%s/%s/cancel" coordinator-url code))
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
  [request {:keys [coordinator-url code] :as lra-context}]
  (some->> (new-lra lra-context)
           (register-request! coordinator-url)
           (assoc {} "long-running-action")
           (#(if (not (string/blank? code))
               (assoc % "long-running-action-parent" code)
               %))
           (#(add-lra-params request lra-context %))))

(defn join!
  [request {:keys [coordinator-url code] :as lra-context}]
  (some->> (new-participant lra-context)
           (#(join-request! coordinator-url code %))
           (#(add-lra-params request lra-context {"long-running-action" %}))))

(defn close!
  [{:keys [coordinator-url code]}]
  (close-request! coordinator-url code))

(defn cancel!
  [{:keys [coordinator-url code]}]
  (cancel-request! coordinator-url code))

(defn lra-request-handler
  [request {:keys [current-lra] :as lra-context}]
  (condp = (:type current-lra)
    :requires-new (start! request lra-context)
    :mandatory (if (string/blank? (:code lra-context))
                 (throw (ex-info "mandatory" {:type :mandatory-context}))
                       ;; else
                 (join! request lra-context))
    ;; otherwise
    (header-lra-params request lra-context)))

(defn lra-response-handler
  [{:keys [current-lra] :as lra-context} resp-code]
  ;; if response code is more then 4XX cancel the lra
  (if (>= (/ resp-code 100) 4) ;; TODO: need real impl.. use it only for pre-alpha
    (do
      (log/infof "cancelling lra %s" (:code lra-context))
      (cancel! lra-context))
    ;; else
    (condp = (:type current-lra)
      :requires-new (do
                      (log/infof "closing lra %s" (:code lra-context))
                      (close! lra-context))
    ;; TODO: implement other types
      nil)))

;; TODO: implement response-error
(defn response-lra-error
  [ex]
  (condp = (-> ex ex-data :type)
    :mandatory-context {:status 412}
    {:status 400}))

(defn -lra-handler
  [request {:keys [current-lra router] :as options}]
  (if (s/valid? ::lra-def current-lra)
    (let [request' (header-lra-params request current-lra)

          lra-context (merge options
                             {:code     (-> request' :lra-params :code str)
                              :base-uri (base-url request')
                              :lra-defs (find-lra-defs router (:id current-lra))})]
      (lra-request-handler request' lra-context))
      ;; else
    (throw (ex-info "invalid lra defination" {:type :spec :data (s/explain-data ::lra-def current-lra)}))))

(defn lra-handler-sync
  [handler request {:keys [current-lra] :as options}]
  (if (seq current-lra) ;; in lra context
    (let [[request' ex] (try [(-lra-handler request options)]
                             (catch Throwable ex
                               [nil ex]))]
      (if ex ;; pre-condition
        (response-lra-error ex)
           ;; ok
        (let [response (handler request')
              _ (future (lra-response-handler (:lra-params request') (:status response)))]
          response)))
       ;; else: not in lra context
    (handler request)))

(defn lra-handler-async
  [handler request respond raise {:keys [current-lra] :as options}]
  (if (seq current-lra) ;; in lra context
    (let [[request' ex] (try [(-lra-handler request options)]
                             (catch Throwable ex
                               [nil ex]))]
      (if ex ;; pre-condition
        (respond (response-lra-error ex))
           ;;ok
        (handler request'
                 (fn [response]
                   (future (lra-response-handler (:lra-params request') (:status response)))
                   (respond response))
                 raise)))

       ;; else: not in lra context
    (handler request respond raise)))
