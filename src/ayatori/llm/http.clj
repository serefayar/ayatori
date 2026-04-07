(ns ayatori.llm.http
  (:require
   [cheshire.core :as json]
   [clojure.core.async :as async]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.net URI]
   [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse HttpResponse$BodyHandlers]
   [java.util.concurrent CompletableFuture]))

(def ^:private ^HttpClient client
  (-> (HttpClient/newBuilder)
      (.build)))

(defn- build-request [url body headers accept-type]
  (let [builder (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" accept-type)
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body))))]
    (doseq [[k v] headers]
      (.header builder k v))
    (.build builder)))

(defn async-post
  "Sends async POST with JSON body. Returns promise-chan of parsed JSON response."
  ([url body] (async-post url body nil))
  ([url body headers]
   (let [ch (async/promise-chan)
         request (build-request url body headers "application/json")]
     (-> ^CompletableFuture (.sendAsync client request (HttpResponse$BodyHandlers/ofString))
         (.thenAccept (fn [^HttpResponse response]
                        (async/put! ch (json/parse-string (.body response) true))))
         (.exceptionally (fn [error]
                           (async/put! ch error)
                           nil)))
     ch)))

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (json/parse-string data true)))))

(defn async-post-stream
  "Sends async POST for SSE streaming. Returns channel that emits parsed chunks."
  ([url body] (async-post-stream url body nil))
  ([url body headers]
   (let [ch (async/chan 32)
         request (build-request url body headers "text/event-stream")]
    (-> ^CompletableFuture (.sendAsync client request (HttpResponse$BodyHandlers/ofInputStream))
        (.thenAccept (fn [^HttpResponse response]
                       (async/thread
                         (try
                           (with-open [reader (io/reader (.body response))]
                             (doseq [line (line-seq reader)]
                               (when-let [data (parse-sse-line line)]
                                 (async/>!! ch data))))
                           (catch Exception e
                             (async/>!! ch e))
                           (finally
                             (async/close! ch))))))
        (.exceptionally (fn [error]
                          (async/put! ch error)
                          (async/close! ch)
                          nil)))
    ch)))
