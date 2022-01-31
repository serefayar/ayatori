(ns ayatori.ring.core
  (:require
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [jsonista.core :as j]
   [clj-http.client :as client]
   [clojure.tools.logging :as log]))

(s/def ::coordinator-url (complement string/blank?))
(s/def ::options (s/keys :req-un [::coordinator-url]))

(s/def ::type #{:required-new
                :required #_notimplented
                :mandatory
                :compansate
                :complete})
(s/def ::end? boolean?)
(s/def ::id keyword?)
(s/def ::cancel-on (s/* int?))
(s/def ::concel-on-family #{:info
                            :success
                            :redirect
                            :client-error
                            :server-error})

(s/def ::lra (s/keys :req-un [::id ::type]
                     :opt-un [::end? ;; not implemented
                              ::cancel-on ;; not implemented
                              ::cancel-on-family ;; not implemented
                              ]))

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

(defn cancel-request!
  [coordinator-url code]
  (->> {:content-type :json
        :socket-timeout 1000
        :connection-timeout 1000}
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

(defn cancel!
  [{:keys [coordinator-url code]}]
  (cancel-request! coordinator-url code))

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

(defn lra-response-handler
  [{:keys [current-lra] :as lra-context} resp-code]
  ;; if response code is more then 4XX cancel the lra
  (if (>= (/ resp-code 100) 4) ;; TODO: need real impl.. use it only for pre-alpha
    (cancel! lra-context)
    ;; else
    (condp = (:type current-lra)
      :required-new (do
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
  (if (s/valid? ::lra current-lra)
    (let [request' (header-lra-params request current-lra)

          lra-context (merge options
                             {:code (-> request' :lra-params :code)
                              :base-uri (base-uri request)
                              :lra-defs (find-lra-defs router (:id current-lra))})]
      (lra-request-handler request' lra-context))
      ;; else
    (throw (ex-info "invalid lra defination" {:type :spec :data (s/explain-data ::lra current-lra)}))))

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
