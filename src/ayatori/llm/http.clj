(ns ayatori.llm.http
  "HTTP client for LLM API calls using hato."
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [hato.client :as hc]))

(def ^:private client (hc/build-http-client {}))

(defn async-post
  "Sends async POST with JSON body. Returns promise-chan of parsed JSON response."
  ([url body] (async-post url body nil))
  ([url body headers]
   (let [ch (async/promise-chan)]
     (hc/post url
              {:http-client client
               :async? true
               :content-type :json
               :accept :json
               :body (json/generate-string body)
               :headers headers}
              (fn [response]
                (async/put! ch (json/parse-string (:body response) true)))
              (fn [error]
                (async/put! ch (ex-info "HTTP request failed" {:url url} error))))
     ch)))

(defn async-post-stream
  "Sends async POST for SSE streaming. Returns channel of parsed events.
   parse-fn is called for each line, should return event map or nil."
  [url body headers parse-fn]
  (let [ch (async/chan 100)]
    (hc/post url
             {:http-client client
              :async? true
              :content-type :json
              :accept "text/event-stream"
              :as :stream
              :body (json/generate-string body)
              :headers headers}
             (fn [response]
               (async/thread
                 (try
                   (with-open [rdr (io/reader (:body response))]
                     (doseq [line (line-seq rdr)]
                       (when-let [event (parse-fn line)]
                         (async/>!! ch event))))
                   (finally
                     (async/close! ch)))))
             (fn [error]
               (async/put! ch (ex-info "Stream request failed" {:url url} error))
               (async/close! ch)))
    ch))
