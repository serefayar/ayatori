(ns ayatori.resilience-test
  (:require
   [ayatori.core :as aya]
   [ayatori.resilience :as res]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(defn- deref! [ch]
  (let [result (async/alt!! ch ([v] v)
                            (async/timeout 10000) ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Deref timeout" {})))
    (if (instance? Throwable result)
      (throw result)
      result)))

(deftest resolve-node-config-test
  (testing "returns nil when no resilience config"
    (is (nil? (res/resolve-node-config nil :order :fetch))))

  (testing "returns defaults when no node-specific config"
    (let [config {:defaults {:timeout-ms 5000}}]
      (is (= {:timeout-ms 5000} (res/resolve-node-config config :order :fetch)))))

  (testing "node-specific config"
    (let [config {:defaults {:timeout-ms 5000}
                  :order {:fetch {:timeout-ms 10000}}}]
      (is (= {:timeout-ms 10000} (res/resolve-node-config config :order :fetch)))))

  (testing "merge defaults with specific"
    (let [config {:defaults {:timeout-ms 5000
                             :retry {:max-retries 1}}
                  :order {:fetch {:timeout-ms 10000}}}]
      (is (= {:timeout-ms 10000 :retry {:max-retries 1}}
             (res/resolve-node-config config :order :fetch)))))

  (testing "other agent uses defaults"
    (let [config {:defaults {:timeout-ms 5000}
                  :order {:fetch {:timeout-ms 10000}}}]
      (is (= {:timeout-ms 5000} (res/resolve-node-config config :inventory :check))))))

(deftest validate-config-test
  (testing "pure node allows all patterns (fn may contain I/O)"
    (is (:valid? (res/validate-config {:timeout-ms 5000} :pure)))
    (is (:valid? (res/validate-config {:retry {:max-retries 1}} :pure)))
    (is (:valid? (res/validate-config {:circuit-breaker {:failure-threshold 3 :delay-ms 1000}} :pure))))

  (testing "router node allows nothing"
    (is (not (:valid? (res/validate-config {:timeout-ms 5000} :router))))
    (is (:valid? (res/validate-config {} :router)))
    (is (= [:timeout-ms] (:invalid-keys (res/validate-config {:timeout-ms 5000} :router)))))

  (testing "llm node allows all patterns"
    (is (:valid? (res/validate-config {:timeout-ms 5000
                                       :retry {:max-retries 1}
                                       :circuit-breaker {:failure-threshold 3 :delay-ms 1000}
                                       :rate-limit {:rate 10}
                                       :bulkhead {:concurrency 5}
                                       :fallback (fn [_ _] nil)}
                                      :llm))))

  (testing "fan-out node allows nothing"
    (is (not (:valid? (res/validate-config {:timeout-ms 5000} :fan-out))))
    (is (:valid? (res/validate-config {} :fan-out)))))

(deftest node-type-validation-at-system-creation-test
  (testing "fan-out node with timeout throws at make-system"
    (let [fan-out-node {:type :fan-out
                        :branches [:a :b]}
          g (aya/make-agent {:nodes {:fan-out-node fan-out-node
                                     :a (fn [x] {:result x})
                                     :b (fn [x] {:result x})}
                             :edges {}
                             :caps {:main {:entry :fan-out-node}}})]
      (is (thrown-with-msg?
           Exception #"Invalid resilience config for node type"
           (aya/make-system {:agents {:test g}
                             :resilience {:test {:fan-out-node {:timeout-ms 5000}}}})))))

  (testing "pure node with all patterns is valid"
    (let [pure-fn (fn [_] {:result :ok})
          g (aya/make-agent {:nodes {:pure-node pure-fn}
                             :edges {}
                             :caps {:main {:entry :pure-node}}})]
      (is (some? (aya/make-system {:agents {:test g}
                                   :resilience {:test {:pure-node {:timeout-ms 5000
                                                                   :circuit-breaker {:failure-threshold 3
                                                                                     :delay-ms 1000}}}}}))))))

(deftest timeout-test
  (testing "node times out"
    (let [slow-fn (fn [_] (Thread/sleep 500) {:result :done})
          g (aya/make-agent {:nodes {:slow slow-fn}
                             :edges {}
                             :caps {:main {:entry :slow}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:slow {:timeout-ms 100}}}})
                  aya/start!)]
      (try
        (is (thrown? Exception (deref! (aya/run sys :test :main {}))))
        (try
          (deref! (aya/run sys :test :main {}))
          (is false "Should have thrown")
          (catch Exception e
            (is (= :timeout (-> e ex-cause ex-data :ayatori/error-type)))))
        (finally
          (aya/stop! sys)))))

  (testing "node completes within timeout"
    (let [fast-fn (fn [input] {:result {:value (:x input)}})
          g (aya/make-agent {:nodes {:fast fast-fn}
                             :edges {}
                             :caps {:main {:entry :fast}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fast {:timeout-ms 5000}}}})
                  aya/start!)]
      (try
        (is (= {:value 42} (deref! (aya/run sys :test :main {:x 42}))))
        (finally
          (aya/stop! sys))))))

(deftest retry-test
  (testing "retries on failure then succeeds"
    (let [call-count (atom 0)
          flaky-fn (fn [_]
                     (swap! call-count inc)
                     (if (< @call-count 3)
                       (throw (ex-info "Transient error" {}))
                       {:result :success}))
          g (aya/make-agent {:nodes {:flaky flaky-fn}
                             :edges {}
                             :caps {:main {:entry :flaky}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:flaky {:retry {:max-retries 3
                                                                        :backoff-ms [10 :constant]}}}}})
                  aya/start!)]
      (try
        (is (= :success (deref! (aya/run sys :test :main {}))))
        (is (= 3 @call-count))
        (finally
          (aya/stop! sys)))))

  (testing "exhausts retries and fails"
    (let [call-count (atom 0)
          always-fail (fn [_]
                        (swap! call-count inc)
                        (throw (ex-info "Permanent error" {})))
          g (aya/make-agent {:nodes {:fail always-fail}
                             :edges {}
                             :caps {:main {:entry :fail}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fail {:retry {:max-retries 2
                                                                       :backoff-ms [10 :constant]}}}}})
                  aya/start!)]
      (try
        (try
          (deref! (aya/run sys :test :main {}))
          (is false "Should have thrown")
          (catch Exception e
            (is (= "Permanent error" (-> e ex-cause ex-message)))))
        (is (= 3 @call-count))
        (finally
          (aya/stop! sys))))))

(deftest defaults-test
  (testing "system defaults apply to all nodes"
    (let [call-count (atom 0)
          flaky-fn (fn [_]
                     (swap! call-count inc)
                     (if (< @call-count 2)
                       (throw (ex-info "Transient" {}))
                       {:result :ok}))
          g (aya/make-agent {:nodes {:flaky flaky-fn}
                             :edges {}
                             :caps {:main {:entry :flaky}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:defaults {:retry {:max-retries 2
                                                                    :backoff-ms [10 :constant]}}}})
                  aya/start!)]
      (try
        (is (= :ok (deref! (aya/run sys :test :main {}))))
        (is (= 2 @call-count))
        (finally
          (aya/stop! sys))))))

(deftest fallback-test
  (testing "fallback called on failure"
    (let [fail-fn (fn [_] (throw (ex-info "Fail" {})))
          g (aya/make-agent {:nodes {:fail fail-fn}
                             :edges {}
                             :caps {:main {:entry :fail}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fail {:fallback (fn [input _e]
                                                                           {:fallback true
                                                                            :input input})}}}})
                  aya/start!)]
      (try
        (is (= {:fallback true :input {:x 1}} (deref! (aya/run sys :test :main {:x 1}))))
        (finally
          (aya/stop! sys)))))

  (testing "fallback called after retry exhaustion"
    (let [call-count (atom 0)
          fail-fn (fn [_]
                    (swap! call-count inc)
                    (throw (ex-info "Fail" {})))
          g (aya/make-agent {:nodes {:fail fail-fn}
                             :edges {}
                             :caps {:main {:entry :fail}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fail {:retry {:max-retries 2
                                                                       :backoff-ms [10 :constant]}
                                                               :fallback (fn [_ _] {:fallback true})}}}})
                  aya/start!)]
      (try
        (is (= {:fallback true} (deref! (aya/run sys :test :main {}))))
        (is (= 3 @call-count))
        (finally
          (aya/stop! sys))))))

(deftest circuit-breaker-test
  (res/reset-all!)
  (testing "circuit breaker opens after failures"
    (let [call-count (atom 0)
          fail-fn (fn [_]
                    (swap! call-count inc)
                    (throw (ex-info "Service down" {})))
          g (aya/make-agent {:nodes {:fail fail-fn}
                             :edges {}
                             :caps {:main {:entry :fail}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fail {:circuit-breaker {:failure-threshold 3
                                                                                  :delay-ms 60000}}}}})
                  aya/start!)]
      (try
        ;; First 3 calls fail and count toward threshold
        (dotimes [_ 3]
          (try (deref! (aya/run sys :test :main {}))
               (catch Exception _)))
        (is (= 3 @call-count))
        ;; Circuit should be open now, next call fails fast without calling fn
        (try
          (deref! (aya/run sys :test :main {}))
          (is false "Should have thrown")
          (catch Exception e
            (is (= :circuit-open (-> e ex-cause ex-data :ayatori/error-type)))))
        (is (= 3 @call-count))
        (finally
          (aya/stop! sys)
          (res/reset-all!))))))

(deftest bulkhead-test
  (res/reset-all!)
  (testing "bulkhead limits concurrency"
    (let [active-count (atom 0)
          max-active (atom 0)
          slow-fn (fn [_]
                    (swap! active-count inc)
                    (swap! max-active max @active-count)
                    (Thread/sleep 100)
                    (swap! active-count dec)
                    {:result :done})
          g (aya/make-agent {:nodes {:slow slow-fn}
                             :edges {}
                             :caps {:main {:entry :slow}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:slow {:bulkhead {:concurrency 2}}}}})
                  aya/start!)]
      (try
        ;; Start 5 concurrent calls
        (let [chs (mapv (fn [_] (aya/run sys :test :main {})) (range 5))]
          (doseq [ch chs]
            (deref! ch)))
        ;; Max active should be limited to 2
        (is (<= @max-active 2))
        (finally
          (aya/stop! sys)
          (res/reset-all!))))))

(deftest rate-limit-test
  (res/reset-all!)
  (testing "rate limiter throttles calls"
    (let [call-count (atom 0)
          fast-fn (fn [_]
                    (swap! call-count inc)
                    {:result :done})
          g (aya/make-agent {:nodes {:fast fast-fn}
                             :edges {}
                             :caps {:main {:entry :fast}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:fast {:rate-limit {:rate 5
                                                                            :period :second}}}}})
                  aya/start!)]
      (try
        ;; Call 5 times quickly (should succeed)
        (dotimes [_ 5]
          (deref! (aya/run sys :test :main {})))
        (is (= 5 @call-count))
        (finally
          (aya/stop! sys)
          (res/reset-all!))))))

(deftest topology-visibility-test
  (testing "describe-system-topology shows resilience wrapper nodes"
    (let [slow-fn (fn [_] {:result :done})
          g (aya/make-agent {:nodes {:slow slow-fn}
                             :edges {}
                             :caps {:main {:entry :slow}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:slow {:timeout-ms 5000
                                                               :retry {:max-retries 2}}}}})
                  aya/start!)]
      (try
        (let [topo (aya/describe-system-topology sys)
              agent-topo (get-in topo [:agents :test])
              procs (:procs agent-topo)]
          ;; Wrapper node should exist with correct naming
          (is (contains? procs :ayatori.res/retry+timeout:slow))
          ;; Wrapper should have metadata
          (let [wrapper-spec (get procs :ayatori.res/retry+timeout:slow)]
            (is (= :slow (get-in wrapper-spec [:wrapper :target])))
            (is (= #{:timeout :retry} (get-in wrapper-spec [:wrapper :patterns])))))
        (finally
          (aya/stop! sys)))))

  (testing "wrapper connections are rewired correctly"
    (let [fn-a (fn [_] {:result :a})
          fn-b (fn [input] {:result (str (:result input) "-b")})
          g (aya/make-agent {:nodes {:a fn-a :b fn-b}
                             :edges {:a :b}
                             :caps {:main {:entry :a}}})
          sys (-> (aya/make-system {:agents {:test g}
                                    :resilience {:test {:b {:timeout-ms 5000}}}})
                  aya/start!)]
      (try
        (let [topo (aya/describe-system-topology sys)
              conns (get-in topo [:agents :test :conns])
              wrapper-key :ayatori.res/timeout:b]
          ;; Connection from :a should go to wrapper
          (is (some #(= % [[:a :out] [wrapper-key :in]]) conns))
          ;; Connection from wrapper should go to :b
          (is (some #(= % [[wrapper-key :out] [:b :in]]) conns)))
        (finally
          (aya/stop! sys))))))
