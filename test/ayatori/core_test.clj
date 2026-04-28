(ns ayatori.core-test
  (:require
   [ayatori.core :as aya]
   [clojure.core.async :as async]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  (fn [f]
    (reset! @#'aya/active-system nil)
    (f)
    (reset! @#'aya/active-system nil)))

(defn- deref! [ch]
  (let [result (async/alt!! ch ([v] v)
                            (async/timeout 5000) ::timeout)]
    (when (= ::timeout result)
      (throw (ex-info "Deref timeout" {})))
    (if (instance? Throwable result)
      (throw result)
      result)))

(defn- run-agent! [agent-spec input]
  (let [sys (-> (aya/make-system {:agents {:test agent-spec}})
                aya/start!)]
    (try
      (deref! (aya/run sys :test :main input))
      (finally
        (aya/stop! sys)))))

(deftest linear-pipeline-test
  (let [g (aya/make-agent {:nodes {:parse  (fn [input] {:result {:text (str "parsed:" (:raw input))}})
                                   :enrich (fn [input] {:result {:text (str "enriched:" (:text input))}})
                                   :score  (fn [input] {:result {:score 0.95 :text (:text input)}})}
                           :edges {:parse :enrich :enrich :score}
                           :caps  {:main {:entry :parse}}})]
    (is (= {:score 0.95 :text "enriched:parsed:hello"}
           (run-agent! g {:raw "hello"})))))

(deftest conditional-branch-test
  (let [g (aya/make-agent {:nodes {:score   (fn [input]
                                              {:result {:confidence (:confidence input)
                                                        :decision (if (> (:confidence input) 0.8)
                                                                    :approved
                                                                    :rejected)}})
                                   :approve (fn [input] {:result {:result :approved :input input}})
                                   :reject  (fn [input] {:result {:result :rejected :input input}})}
                           :edges {:score [[:approve #(> (:confidence %) 0.8)]
                                           [:reject]]}
                           :caps  {:main {:entry :score}}})]
    (is (= {:result :approved :input {:confidence 0.9 :decision :approved}}
           (run-agent! g {:confidence 0.9})))
    (is (= {:result :rejected :input {:confidence 0.5 :decision :rejected}}
           (run-agent! g {:confidence 0.5})))))

(deftest error-propagation-test
  (let [g (aya/make-agent {:nodes {:a (fn [_] (throw (ex-info "boom" {})))
                                   :b (fn [input] {:result input})}
                           :edges {:a :b}
                           :caps  {:main {:entry :a}}})]
    (is (thrown-with-msg? Exception #"Node execution failed" (run-agent! g {})))))

(defn- mock-invoke [responses]
  (let [idx (atom 0)]
    (fn [_client _params]
      (let [i @idx
            _ (swap! idx inc)]
        (doto (async/promise-chan) (async/put! (nth responses i)))))))

(deftest llm-text-response-test
  (let [invoke-fn (mock-invoke [{:role :assistant :content "Hello!"}])
        g (aya/make-agent {:nodes {:llm    {:type :llm :invoke-fn invoke-fn :prompt "You are helpful"}
                                   :output (fn [input] {:result {:answer (:content input)}})}
                           :edges {:llm [[:done :output]]}
                           :caps  {:main {:entry :llm}}})]
    (is (= {:answer "Hello!"} (run-agent! g {:content "Hi"})))))

(deftest llm-tool-call-test
  (let [invoke-fn (mock-invoke
                   [{:role :assistant
                     :tool-calls [{:id "call_1"
                                   :function {:name "search" :arguments {:query "clojure"}}}]}
                    {:role :assistant :content "Found results"}])
        g (aya/make-agent {:nodes {:llm    {:type :llm :invoke-fn invoke-fn}
                                   :search (fn [input] {:result (str "results for " (:query input))})
                                   :output (fn [input] {:result {:answer (:content input)}})}
                           :edges {:llm [[:search :search] [:done :output]] :search :llm}
                           :caps  {:main {:entry :llm}}})]
    (is (= {:answer "Found results"} (run-agent! g {:content "search clojure"})))))

(deftest fan-out-test
  (let [g (aya/make-agent {:nodes {:fan       {:type :fan-out :branches [:sentiment :toxicity]}
                                   :sentiment (fn [_] {:result {:label :positive}})
                                   :toxicity  (fn [_] {:result {:label :safe}})
                                   :aggregate (fn [input]
                                                {:result {:sentiment (get-in input [:results :sentiment :label])
                                                          :toxicity  (get-in input [:results :toxicity :label])}})}
                           :edges {:fan :aggregate}
                           :caps  {:main {:entry :fan}}})]
    (is (= {:sentiment :positive :toxicity :safe} (run-agent! g {:text "hello"})))))

(deftest dep-wiring-test
  (let [doubler (aya/make-agent {:nodes {:dbl (fn [input] {:result {:doubled (* 2 (:n input))}})}
                                 :edges {}
                                 :caps  {:main {:entry :dbl}}})
        caller  (aya/make-agent {:nodes {:prep (fn [input] {:result {:n (inc (:v input))}})}
                                 :edges {:prep :double}
                                 :deps  [:double]
                                 :caps  {:main {:entry :prep}}})
        sys (-> (aya/make-system {:agents {:caller caller :doubler doubler}
                                  :wiring {:caller {:double [:doubler :main]}}})
                aya/start!)]
    (is (= {:doubled 12} (deref! (aya/run sys :caller :main {:v 5}))))
    (aya/stop! sys)))

(deftest system-lifecycle-test
  (let [g   (aya/make-agent {:nodes {:echo (fn [input] {:result {:echoed input}})}
                             :edges {}
                             :caps  {:main {:entry :echo}}})
        sys (-> (aya/make-system {:agents {:echo g}}) aya/start!)]
    (is (= {:echoed {:msg "hi"}} (deref! (aya/run sys :echo :main {:msg "hi"}))))
    (aya/stop! sys)
    (is (thrown-with-msg? Exception #"not started"
                          (deref! (aya/run sys :echo :main {:msg "hi"}))))))

(defn reloadable-node [input]
  {:result {:version 1 :data input}})

(deftest var-reloadability-test
  (testing "vars are deref'd at call time for REPL reloadability"
    (let [g   (aya/make-agent {:nodes {:process #'reloadable-node}
                               :edges {}
                               :caps  {:main {:entry :process}}})
          sys (-> (aya/make-system {:agents {:test g}}) aya/start!)]
      (is (= {:version 1 :data {:x 1}} (deref! (aya/run sys :test :main {:x 1}))))
      (with-redefs [reloadable-node (fn [input] {:result {:version 2 :data input}})]
        (is (= {:version 2 :data {:x 2}} (deref! (aya/run sys :test :main {:x 2})))))
      (aya/stop! sys))))

(deftest topology-inspection-test
  (testing "topology available before start!"
    (let [g (aya/make-agent {:nodes {:llm    {:type :llm
                                              :invoke-fn (fn [_ _] (async/promise-chan))
                                              :prompt "test"}
                                     :search (fn [_] {:result "found"})}
                             :edges {:llm [[:search :search]]
                                     :search :llm}
                             :deps  [:external]
                             :caps  {:main {:entry :llm}}})
          topology (aya/describe-topology g)]
      (is (map? topology))
      (is (= :llm (:entry-key topology)))
      (is (= #{:external} (:deps topology)))
      (is (contains? (:procs topology) :llm))
      (is (contains? (:procs topology) :search))
      (is (contains? (:procs topology) :external))
      (is (contains? (get-in topology [:node-types :llm]) :llm))
      (is (contains? (get-in topology [:node-types :pure]) :search))
      (is (contains? (get-in topology [:node-types :dep]) :external))
      (is (seq (:conns topology))))))

(deftest dispatch-predicate-multi-route-test
  (testing "dispatch predicates with multiple routes"
    (let [g (aya/make-agent {:nodes {:classify (fn [input]
                                                 {:result {:score (:value input)}})
                                     :high     (fn [input] {:result {:tier :premium :score (:score input)}})
                                     :medium   (fn [input] {:result {:tier :standard :score (:score input)}})
                                     :low      (fn [input] {:result {:tier :basic :score (:score input)}})}
                             :edges {:classify [[:high   #(> (:score %) 90)]
                                                [:medium #(> (:score %) 50)]
                                                [:low]]}
                             :caps  {:main {:entry :classify}}})]
      (is (= {:tier :premium :score 95} (run-agent! g {:value 95})))
      (is (= {:tier :standard :score 70} (run-agent! g {:value 70})))
      (is (= {:tier :basic :score 30} (run-agent! g {:value 30}))))))

(deftest dispatch-predicate-no-match-test
  (testing "throws when no dispatch route matches"
    (let [g (aya/make-agent {:nodes {:check (fn [_] {:result {:status :unknown}})
                                     :ok    (fn [_] {:result :ok})
                                     :err   (fn [_] {:result :error})}
                             :edges {:check [[:ok  #(= :ok (:status %))]
                                             [:err #(= :error (:status %))]]}
                             :caps  {:main {:entry :check}}})]
      (is (thrown-with-msg? Exception #"No matching dispatch route"
                            (run-agent! g {}))))))

(deftest node-schema-test
  (testing "node with schema, cap inherits"
    (let [g (aya/make-agent {:nodes {:process {:fn (fn [input] {:result {:doubled (* 2 (:n input))}})
                                               :input [:map [:n :int]]
                                               :output [:map [:doubled :int]]}}
                             :edges {}
                             :caps {:main {:entry :process}}})]
      (is (= {:doubled 10} (run-agent! g {:n 5})))))

  (testing "cap overrides node schema"
    (let [g (aya/make-agent {:nodes {:process {:fn (fn [input] {:result {:value (:x input)}})
                                               :input [:map [:n :int]]
                                               :output [:map [:value :int]]}}
                             :edges {}
                             :caps {:main {:entry :process
                                           :input [:map [:x :int]]}}})]
      (is (= {:value 42} (run-agent! g {:x 42})))))

  (testing "backward compat with plain function nodes"
    (let [g (aya/make-agent {:nodes {:echo (fn [input] {:result input})}
                             :edges {}
                             :caps {:main {:entry :echo}}})]
      (is (= {:msg "hi"} (run-agent! g {:msg "hi"})))))

  (testing "schema validation fails on invalid input"
    (let [g (aya/make-agent {:nodes {:process {:fn (fn [input] {:result {:doubled (* 2 (:n input))}})
                                               :input [:map [:n :int]]}}
                             :edges {}
                             :caps {:main {:entry :process}}})]
      (is (thrown-with-msg? Exception #"validation failed"
                            (run-agent! g {:n "not-an-int"})))))

  (testing "edge schema incompatibility detected at make-agent"
    (is (thrown-with-msg? Exception #"Edge schema incompatibility"
                          (aya/make-agent {:nodes {:a {:fn (fn [_] {:result {:x 1}})
                                                       :output [:map [:x :int]]}
                                                   :b {:fn (fn [_] {:result {}})
                                                       :input [:map [:y :string]]}}
                                           :edges {:a :b}
                                           :caps {:main {:entry :a}}}))))

  (testing "compatible schemas pass validation"
    (let [g (aya/make-agent {:nodes {:a {:fn (fn [_] {:result {:x 1 :y "hi"}})
                                         :output [:map [:x :int] [:y :string]]}
                                     :b {:fn (fn [input] {:result input})
                                         :input [:map [:x :int]]}}
                             :edges {:a :b}
                             :caps {:main {:entry :a}}})]
      (is (= {:x 1 :y "hi"} (run-agent! g {}))))))

(deftest llm-streaming-test
  (testing "streaming returns tokens then final result"
    (let [tokens (atom [])
          stream-fn (fn [_config _input _state]
                      (let [ch (async/chan)]
                        (async/go
                          (doseq [d ["Hello" " " "world" "!"]]
                            (async/>! ch {:type :delta :delta d}))
                          (async/>! ch {:type :done
                                        :message {:role :assistant
                                                  :content "Hello world!"}})
                          (async/close! ch))
                        ch))
          g (aya/make-agent {:nodes {:llm {:type :llm
                                           :stream true
                                           :invoke-fn (fn [_ _] (async/promise-chan))}}
                             :edges {}
                             :caps {:main {:entry :llm}}})
          sys (-> (aya/make-system {:agents {:test g}}) aya/start!)]
      (with-redefs [ayatori.graph.llm/start-stream stream-fn]
        (let [ch (aya/run sys :test :main {:content "Hi"})]
          (loop []
            (when-let [msg (async/<!! ch)]
              (if (:type msg)
                (do (swap! tokens conj (:delta msg)) (recur))
                (do
                  (is (= ["Hello" " " "world" "!"] @tokens))
                  (is (= {:role :assistant :content "Hello world!"} msg))))))))
      (aya/stop! sys))))

