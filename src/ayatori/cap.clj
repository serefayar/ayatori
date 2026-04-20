(ns ayatori.cap
  (:import
   [java.net URI]))

(defn make-uri
  "Constructs a capability URI: ayatori://host:port/c/agent/cap"
  [host port agent-name cap-name]
  (format "ayatori://%s:%d/c/%s/%s" host port (name agent-name) (name cap-name)))

(defn parse-uri
  "Parses a capability URI string into {:host :port :agent :cap}."
  [uri-str]
  (try
    (let [u (URI. uri-str)]
      (when-not (= "ayatori" (.getScheme u))
        (throw (ex-info "Invalid scheme" {:uri uri-str :scheme (.getScheme u)})))
      (let [path (.getPath u)
            [_ agent-str cap-str] (when path (re-matches #"/c/([^/]+)/(.+)" path))]
        (when-not (and agent-str cap-str)
          (throw (ex-info "Invalid path, expected /c/<agent>/<cap>" {:uri uri-str :path path})))
        {:host (.getHost u)
         :port (.getPort u)
         :agent (keyword agent-str)
         :cap (keyword cap-str)}))
    (catch java.net.URISyntaxException e
      (throw (ex-info "Malformed URI" {:uri uri-str} e)))))

(deftype CapHandle [uri metadata]
  clojure.lang.IDeref
  (deref [_] (cond-> {:uri uri}
               metadata (assoc :metadata metadata))))

(defn cap-handle? [x]
  (instance? CapHandle x))

(defn cap-uri [^CapHandle ch] (.uri ch))

(defn cap-metadata [^CapHandle ch] (.metadata ch))

(defn make-cap-handle
  "Creates a CapHandle with URI and optional metadata."
  ([uri] (CapHandle. uri nil))
  ([uri metadata] (CapHandle. uri metadata)))

(defn describe
  "Returns endpoint schema from a CapHandle. nil if no schema defined."
  [ch]
  (not-empty (select-keys (cap-metadata ch) [:input :output])))

(defmethod print-method CapHandle [ch ^java.io.Writer w]
  (.write w (str "#cap<" (cap-uri ch) ">")))
