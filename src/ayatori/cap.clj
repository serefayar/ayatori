(ns ayatori.cap
  (:import
   [java.net URI]))

(defn make-uri
  "Constructs a capability URI: ayatori://host:port/c/ref"
  [host port ref]
  (str "ayatori://" host ":" port "/c/" ref))

(defn parse-uri
  "Parses a capability URI string into {:host :port :ref}."
  [uri-str]
  (try
    (let [u (URI. uri-str)]
      (when-not (= "ayatori" (.getScheme u))
        (throw (ex-info "Invalid scheme" {:uri uri-str :scheme (.getScheme u)})))
      (let [path (.getPath u)
            ref  (when (and path (.startsWith path "/c/"))
                   (subs path 3))]
        (when-not ref
          (throw (ex-info "Invalid path, expected /c/<ref>" {:uri uri-str :path path})))
        {:host (.getHost u)
         :port (.getPort u)
         :ref  ref}))
    (catch java.net.URISyntaxException e
      (throw (ex-info "Malformed URI" {:uri uri-str} e)))))

(defn make-ref
  "Generates an opaque capability ref (UUID string)."
  []
  (str (random-uuid)))

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
  [^CapHandle ch]
  (let [m (.metadata ch)]
    (when (or (:input m) (:output m))
      (select-keys m [:input :output]))))

(defmethod print-method CapHandle [^CapHandle ch ^java.io.Writer w]
  (.write w (str "#cap<" (.uri ch) ">")))
