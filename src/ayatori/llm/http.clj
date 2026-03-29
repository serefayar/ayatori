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

(defn async-post
  "Sends async POST with JSON body. Returns promise-chan of parsed JSON response."
  [url body]
  (let [ch (async/promise-chan)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "application/json")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                    (.build))]
    (-> ^CompletableFuture (.sendAsync client request (HttpResponse$BodyHandlers/ofString))
        (.thenAccept (fn [^HttpResponse response]
                       (async/put! ch (json/parse-string (.body response) true))))
        (.exceptionally (fn [error]
                          (async/put! ch error)
                          nil)))
    ch))

(defn- parse-sse-line [line]
  (when (str/starts-with? line "data: ")
    (let [data (subs line 6)]
      (when-not (= data "[DONE]")
        (json/parse-string data true)))))

(defn async-post-stream
  "Sends async POST for SSE streaming. Returns channel that emits parsed chunks."
  [url body]
  (let [ch (async/chan 32)
        request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create url))
                    (.header "Content-Type" "application/json")
                    (.header "Accept" "text/event-stream")
                    (.POST (HttpRequest$BodyPublishers/ofString (json/generate-string body)))
                    (.build))]
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
    ch))
