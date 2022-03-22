(ns ayatori.ring.core-test
  (:require [ayatori.ring.core :as core]
            [clojure.test :as t :refer [deftest is testing]]
            [reitit.ring :as ring]
            [reitit.core :as r]
            [clojure.spec.alpha :as s]
            [clj-http.client :as client]))

(def router-fixture
  (ring/router
   ["/service1"
    ["/ping"
     {:get {:handler (fn [& _])}}]
    ["/order"
     {:lra {:id :test
            :type :requires-new}
      :post {:handler (fn [& _])}}]
    ["/compensate"
     {:lra {:id :test
            :type :compensate}
      :put {:handler (fn [& _])}}]
    ["/complete"
     {:lra {:id :test
            :type :complete}
      :put {:handler (fn [& _])}}]]))

(def lra-defs-fixture
  [{:id :test, :type :requires-new, :route "/service1/order"}
   {:id :test, :type :compensate, :route "/service1/compensate"}
   {:id :test, :type :complete, :route "/service1/complete"}])

(def lra-code (.toString (java.util.UUID/randomUUID)))
(def base-uri "http://localhost:3030")

(def lra-context-fixture
  {:code lra-code
   :base-uri base-uri
   :lra-defs lra-defs-fixture
   :current-lra (first lra-defs-fixture)})

(def header-params {"long-running-action" lra-code})

(deftest base-uri-test
  (testing "it should return valid"
    (is (uri? (java.net.URI. (core/base-url {:scheme "http" :server-name "localhost" :server-port 3030})))))

  (testing "it should throw exception given malformed url"
    (is (uri? (java.net.URI. (core/base-url {}))))))

(deftest create-acts-test
  (testing "given lra context it should return acts"
    (let [ret (core/create-acts lra-context-fixture)]
      (is (seq ret))
      (is (= [:compensate :complete] (map :act/type ret)))
      (is (= [:compensate :complete] (map :act/type ret)))
      (is (every? #(uri? (.toURI (java.net.URL. (:act/url %)))) ret))))

  (testing "return nil if lra-defs is missing in the context"
    (is (empty? (core/create-acts (assoc lra-context-fixture :lra-defs nil)))))

  (testing "return nil if current-lra is missing in the context"
    (is (empty? (core/create-acts (assoc lra-context-fixture :current-lra nil))))))

(deftest new-lra-test
  (testing "given lra context it should return valid lra record"
    (is (s/valid? ::core/lra (core/new-lra lra-context-fixture))))

  (testing "it should return nil when lra context is nil or {}"
    (is (nil? (core/new-lra nil)))))

(deftest new-participant-test
  (testing "given lra context it should return valid participant record"
    (is (s/valid? ::core/participant (core/new-participant lra-context-fixture))))

  (testing "it should return nil when lra context is nil or {}"
    (is (nil? (core/new-participant nil)))))

(deftest new-request-test
  (testing "when body param is null it should return valid request"
    (is (s/valid? ::core/request (core/new-request))))

  (testing "given body param it should return valid request"
    (is (s/valid? ::core/request (core/new-request {:a 1})))))

(deftest register-request!-test
  (testing "given coordinator url and lra it it should return response body"
    (with-redefs [client/post (fn [_ lra] {:body (:lra/code lra)})]
      (is (= (:lra/code lra-context-fixture) (core/register-request! base-uri lra-context-fixture))))))

(deftest join-request!-test
  (testing "given coordinator url, code and participant it should return response body"
    (with-redefs [client/put (fn [_ lra] {:body (:lra/code lra)})]
      (is (= (:lra/code lra-context-fixture) (core/join-request! base-uri lra-code lra-context-fixture))))))

(deftest close-request!-test
  (testing "given coordinator url and lra-code it it should return response body"
    (with-redefs [client/put (fn [_ lra] {:body (:lra/code lra)})]
      (is (= (:lra/code lra-context-fixture) (core/close-request! base-uri lra-code))))))

(deftest cancel-request!-test
  (testing "given coordinator url and lra-code it it should return response body"
    (with-redefs [client/put (fn [_ lra] {:body (:lra/code lra)})]
      (is (= (:lra/code lra-context-fixture) (core/cancel-request! base-uri lra-code))))))

(deftest add-lra-params-test
  (testing "given header params and lra context is should return update request map"
    (let [ret (core/add-lra-params {} lra-context-fixture header-params)]
      (is (= lra-code (-> ret :lra-params :code)))
      (is (= header-params (-> ret :lra-headers))))))

(deftest collect-lra-headers-test
  (testing "given request with lra headers it souold return lra headers map"
    (let [ret (core/collect-lra-headers {:headers header-params})]
      (is (= ret header-params)))))

(deftest header-lra-params-test
  (testing "given request and lra context it sould return updated request with lra-params"
    (let [ret (core/header-lra-params {:headers header-params} lra-context-fixture)]
      (is (= header-params (-> ret :lra-headers)))
      (is (= lra-code (-> ret :lra-params :code))))))

(deftest start!-test
  (testing "given lra context is should return updated request with lra-params"
    (with-redefs [core/register-request! (fn [& _] lra-code)]
      (let [ret (core/start! {:headers header-params} lra-context-fixture)]
        (is (= header-params (-> ret :lra-headers)))
        (is (= lra-code (-> ret :lra-params :code)))))))

(deftest join!-test
  (testing "given lra context is should return updated request with lra-params"
    (with-redefs [core/join-request! (fn [& _] lra-code)]
      (let [ret (core/join! {:headers header-params} lra-context-fixture)]
        (is (= header-params (-> ret :lra-headers)))
        (is (= lra-code (-> ret :lra-params :code)))))))

(deftest close!-test
  (testing "given lra context (coordinator-url and code) it should return lra-code"
    (with-redefs [core/close-request! (fn [& _] lra-code)]
      (is (= lra-code (core/close! lra-context-fixture))))))

(deftest cancel!-test
  (testing "given lra context (coordinator-url and code) it should return lra-code"
    (with-redefs [core/cancel-request! (fn [& _] lra-code)]
      (is (= lra-code (core/cancel! lra-context-fixture))))))

(deftest lra-request-handler
  (with-redefs [core/register-request! (fn [& _] lra-code)
                core/join-request! (fn [& _] lra-code)]
    (testing "given lra context (lra type required-new) it should return updated request with lra"
      (let [ret (core/lra-request-handler {} lra-context-fixture)]
        (is (= lra-code (-> ret :lra-params :code)))
        (is (= header-params (-> ret :lra-headers)))))

    (testing "given lra context (lra type mandatory) if lra-code is blank it should throw exception"
      (is (thrown? Exception (core/lra-request-handler {} (assoc lra-context-fixture
                                                                 :code ""
                                                                 :current-lra {:type :mandatory})))))

    (testing "given lra context (lra type mandatory) if lra-code is not blank it should throw lra code"
      (let [ret (core/lra-request-handler {} (assoc lra-context-fixture
                                                    :current-lra {:type :mandatory}))]
        (is (= lra-code (-> ret :lra-params :code)))
        (is (= header-params (-> ret :lra-headers)))))

    (testing "given lra context (lra type mandatory) if lra-code is not blank it should throw lra code"
      (let [ret (core/lra-request-handler {} (assoc lra-context-fixture
                                                    :current-lra {:type :not-found}))]
        (is (= lra-code (-> ret :lra-params :code)))
        (is (empty? (-> ret :lra-headers)))))))

(deftest lra-response-handler-test
  (with-redefs [core/cancel! (fn [& _] lra-code)
                core/close! (fn [& _] lra-code)]

    (testing "given lra context and response code equal or more then 400 and type of current lra is requires-new return lra code"
      (is (= lra-code (core/lra-response-handler lra-context-fixture 400))))

    (testing "given lra context and response code less then 400 and type type of current lra is required-new it shoold return lra-code"
      (is (= lra-code (core/lra-response-handler lra-context-fixture 200))))

    (testing "given lra context and response code equal or more then 400 and type of current lra is unkown return nil"
      (is (nil? (core/lra-response-handler (assoc-in lra-context-fixture
                                                     [:current-lra :type] :mandatory) 200))))))
(deftest lra-response-error-test
  (testing "given ex-info when ex data type is mandatory-context it should return response with status 412"
    (is (= {:status 412} (core/response-lra-error (ex-info "boom" {:type :mandatory-context})))))

  (testing "given ex-info when ex data type is unknown it should return response with status 400"
    (is (= {:status 400} (core/response-lra-error (ex-info "boom" {:type :not-found}))))))

(deftest -lra-handler-test
  (with-redefs [core/register-request! (fn [& _] lra-code)
                core/join-request! (fn [& _] lra-code)]
    (testing "given not valid current lra it should throw exception"
      (is (thrown? Exception (core/-lra-handler {} {:current-lra nil}))))

    (testing "given valid current lra it should return request with lra context"
      (let [current-lra (-> lra-context-fixture :lra-defs first)
            router (r/compiled-routes router-fixture)
            ret (core/-lra-handler {:headers header-params} {:current-lra current-lra
                                                             :router router})]
        (is (= header-params (-> ret :lra-headers)))
        (is (= lra-code (-> ret :lra-params :code)))
        (is (= current-lra (-> ret :lra-params :current-lra)))

        (is (= router (-> ret :lra-params :router)))))))
