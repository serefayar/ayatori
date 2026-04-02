(ns ayatori.core-test
  (:require
   [ayatori.core :as aya]
   [ayatori.middleware :as mw]
   [clojure.core.async :as async]
   [clojure.string :as str]
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

(defn- run-agent!
  "Creates a single-agent system, starts it, runs the agent, stops it."
  [agent-spec input]
  (let [sys (-> (aya/make-system {:agents {:test agent-spec}})
                aya/start!)]
    (try
      (deref! (aya/run sys :test :main input))
      (finally
        (aya/stop! sys)))))

(deftest linear-pipeline-test
  (testing "three nodes in sequence produce final result"
    (let [g (aya/make-agent {
                             :nodes {:parse  (fn [input _] {:result {:text (str "parsed:" (:raw input))}})
                                     :enrich (fn [input _] {:result {:text (str "enriched:" (:text input))}})
                                     :score  (fn [input _] {:result {:score 0.95 :text (:text input)}})}
                             :edges {:parse  :enrich
                                     :enrich :score}
                             :caps  {:main {:entry :parse}}})]
      (is (= {:score 0.95 :text "enriched:parsed:hello"}
             (run-agent! g {:raw "hello"}))))))

(deftest conditional-branch-test
  (testing "node output :route determines next node"
    (let [g (aya/make-agent {
                             :nodes {:score   (fn [input _]
                                                {:result (if (> (:confidence input) 0.8)
                                                           {:route :approve :data {:decision :approved}}
                                                           {:route :reject :data {:decision :rejected}})})
                                     :approve (fn [input _] {:result {:result :approved :input input}})
                                     :reject  (fn [input _] {:result {:result :rejected :input input}})}
                             :edges {:score {:approve :approve
                                             :reject  :reject}}
                             :caps  {:main {:entry :score}}})]
      (testing "high confidence -> approve"
        (is (= {:result :approved :input {:decision :approved}}
               (run-agent! g {:confidence 0.9}))))
      (testing "low confidence -> reject"
        (is (= {:result :rejected :input {:decision :rejected}}
               (run-agent! g {:confidence 0.5})))))))

(deftest conditional-edge-error-test
  (testing "unknown route key throws"
    (let [g (aya/make-agent {
                             :nodes {:router (fn [_ _] {:result {:route :unknown :data {}}})
                                     :a      (fn [input _] {:result input})}
                             :edges {:router {:known :a}}
                             :caps  {:main {:entry :router}}})]
      (is (thrown-with-msg? Exception #"Unknown route"
                            (run-agent! g {})))))
  (testing "conditional edge without :route in output throws"
    (let [g (aya/make-agent {
                             :nodes {:router (fn [_ _] {:result {:just "data"}})
                                     :a      (fn [input _] {:result input})}
                             :edges {:router {:x :a}}
                             :caps  {:main {:entry :router}}})]
      (is (thrown-with-msg? Exception #"missing :route"
                            (run-agent! g {}))))))

(deftest cycle-test
  (testing "graph supports cycles with max-steps guard"
    (let [attempt (atom 0)
          g (aya/make-agent {
                             :nodes     {:generate (fn [_ _]
                                                     (swap! attempt inc)
                                                     {:result {:value (* @attempt 10) :attempt @attempt}})
                                         :validate (fn [input _]
                                                     {:result (if (>= (:value input) 30)
                                                                {:route :done :data {:final (:value input)}}
                                                                {:route :retry :data input})})}
                             :edges     {:generate :validate
                                         :validate {:retry :generate
                                                    :done  :ayatori/done}}
                             :caps      {:main {:entry :generate}}
                             :max-steps 20})]
      (is (= {:final 30} (run-agent! g {})))
      (is (= 3 @attempt)))))

(deftest max-steps-exceeded-test
  (testing "exceeding max-steps throws"
    (let [g (aya/make-agent {
                             :nodes     {:a (fn [input _] {:result {:v (inc (or (:v input) 0))}})
                                         :b (fn [input _] {:result input})}
                             :edges     {:a :b :b :a}
                             :caps      {:main {:entry :a}}
                             :max-steps 5})]
      (is (thrown-with-msg? Exception #"max steps"
                            (run-agent! g {}))))))

(deftest error-propagation-test
  (testing "node exception propagates to caller"
    (let [g (aya/make-agent {
                             :nodes {:a (fn [_ _] (throw (ex-info "boom" {:cause :test})))
                                     :b (fn [input _] {:result input})}
                             :edges {:a :b}
                             :caps  {:main {:entry :a}}})]
      (is (thrown-with-msg? Exception #"boom"
                            (run-agent! g {}))))))

(deftest unreachable-node-test
  (testing "node not referenced by any cap entry, edge, or fan-out branch is rejected"
    (is (thrown-with-msg? Exception #"Invalid graph spec"
                          (aya/make-agent {
                                           :nodes {:a      (fn [input _] {:result input})
                                                   :orphan (fn [_ _] {:result :never-called})}
                                           :edges {}
                                           :caps  {:main {:entry :a}}})))))

(deftest stateful-node-test
  (testing "stateful node accumulates state across nodes in same execution"
    (let [g (aya/make-agent {
                             :nodes     {:counter (fn [_ state]
                                                    (let [n (inc (:count state 0))]
                                                      {:result {:count n}
                                                       :state  {:count n}}))
                                         :check   (fn [input _]
                                                    {:result (if (>= (:count input) 3)
                                                               {:route :done :data {:final-count (:count input)}}
                                                               {:route :again :data input})})}
                             :edges     {:counter :check
                                         :check   {:again :counter
                                                   :done  :ayatori/done}}
                             :caps      {:main {:entry :counter}}
                             :lifecycle {:on-start (fn [_] {:count 0})}
                             :max-steps 20})]
      (is (= {:final-count 3} (run-agent! g {}))))))

(deftest concurrent-stateful-shared-state-test
  (testing "two concurrent runs on same agent share state"
    (let [g   (aya/make-agent {
                               :nodes     {:counter (fn [input state]
                                                      (let [n (inc (:count state 0))]
                                                        (Thread/sleep (long (or (:delay input) 0)))
                                                        {:result {:count n}
                                                         :state  {:count n}}))}
                               :edges     {}
                               :caps      {:main {:entry :counter}}
                               :lifecycle {:on-start (fn [_] {:count 0})}})
          sys (-> (aya/make-system {:agents {:test g}}) aya/start!)
          ch1 (aya/run sys :test :main {:delay 10})
          ch2 (aya/run sys :test :main {:delay 10})
          r1 (deref! ch1)
          r2 (deref! ch2)]
      (is (contains? #{1 2} (:count r1)))
      (is (contains? #{1 2} (:count r2)))
      (is (= 2 (+ (:count r1) (:count r2))))
      (aya/stop! sys))))

;;  LLM node

(defn- mock-invoke
  "Creates a mock LLM invoke fn that returns responses in sequence."
  [responses]
  (let [idx (atom 0)]
    (fn [_client _params]
      (let [i @idx
            _ (swap! idx inc)]
        (doto (async/promise-chan) (async/put! (nth responses i)))))))

(deftest llm-text-response-test
  (testing "LLM node with simple text response routes to :done"
    (let [invoke-fn (mock-invoke [{:role :assistant :content "Hello!"}])
          g (aya/make-agent {
                             :nodes {:llm    {:type      :llm
                                              :client    {}
                                              :invoke-fn invoke-fn
                                              :prompt    "You are helpful"}
                                     :output (fn [input _] {:result {:answer (:content input)}})}
                             :edges {:llm {:done :output}}
                             :caps  {:main {:entry :llm}}})]
      (is (= {:answer "Hello!"}
             (run-agent! g {:content "Hi"}))))))

(deftest llm-single-tool-call-test
  (testing "LLM node dispatches tool call and processes result"
    (let [invoke-fn (mock-invoke
                     [{:role       :assistant
                       :tool-calls [{:id       "call_1"
                                     :function {:name      "search"
                                                :arguments {:query "clojure"}}}]}
                      {:role :assistant :content "Found results for clojure"}])
          g (aya/make-agent {
                             :nodes {:llm    {:type      :llm
                                              :client    {}
                                              :invoke-fn invoke-fn}
                                     :search (fn [input _]
                                               {:result (str "results for " (:query input))})
                                     :output (fn [input _] {:result {:answer (:content input)}})}
                             :edges {:llm    {:done   :output
                                              :search :search}
                                     :search :llm}
                             :caps  {:main {:entry :llm}}})]
      (is (= {:answer "Found results for clojure"}
             (run-agent! g {:content "search clojure"}))))))

(deftest llm-multi-tool-call-test
  (testing "LLM node handles multiple tool calls sequentially"
    (let [invoke-fn (mock-invoke
                     [{:role       :assistant
                       :tool-calls [{:id       "call_1"
                                     :function {:name      "search"
                                                :arguments {:q "a"}}}
                                    {:id       "call_2"
                                     :function {:name      "calc"
                                                :arguments {:expr "1+1"}}}]}
                      {:role :assistant :content "Done with both"}])
          g (aya/make-agent {
                             :nodes {:llm    {:type      :llm
                                              :client    {}
                                              :invoke-fn invoke-fn}
                                     :search (fn [input _] {:result (str "found:" (:q input))})
                                     :calc   (fn [input _] {:result (str "computed:" (:expr input))})
                                     :output (fn [input _] {:result {:answer (:content input)}})}
                             :edges {:llm    {:done   :output
                                              :search :search
                                              :calc   :calc}
                                     :search :llm
                                     :calc   :llm}
                             :caps  {:main {:entry :llm}}})]
      (is (= {:answer "Done with both"}
             (run-agent! g {:content "do both"}))))))

(deftest llm-structured-output-test
  (testing "LLM node with response-format returns parsed structured data"
    (let [invoke-fn (mock-invoke [{:name "Alice" :total 99.5}])
          g (aya/make-agent {
                             :nodes {:llm    {:type            :llm
                                              :client          {}
                                              :invoke-fn       invoke-fn
                                              :response-format {:type   :json-schema
                                                                :schema [:map
                                                                         [:name :string]
                                                                         [:total :double]]}}
                                     :output (fn [input _] {:result input})}
                             :edges {:llm {:done :output}}
                             :caps  {:main {:entry :llm}}})]
      (is (= {:name "Alice" :total 99.5}
             (run-agent! g {:content "extract order"}))))))

(deftest llm-self-healing-validation-test
  (testing "LLM retries when response fails Malli validation"
    (let [invoke-fn (mock-invoke [{:wrong "format"}
                                  {:name "Alice" :total 99.5}])
          g (aya/make-agent
             {
              :nodes {:llm {:type            :llm
                            :client          {}
                            :invoke-fn       invoke-fn
                            :response-format {:type        :json-schema
                                              :schema      [:map [:name :string] [:total :double]]
                                              :max-retries 2}}}
              :edges {:llm {:done :ayatori/done}}
              :caps  {:main {:entry :llm}}})]
      (is (= {:name "Alice" :total 99.5}
             (run-agent! g {:content "extract"}))))))

(deftest llm-max-turns-test
  (testing "LLM node throws when max turns exceeded"
    (let [invoke-fn (mock-invoke (repeat 100 {:role :assistant :content "loop"}))
          g (aya/make-agent {
                             :nodes {:llm     {:type      :llm
                                               :client    {}
                                               :invoke-fn invoke-fn
                                               :max-turns 2}
                                     :process (fn [input _] {:result input})}
                             :edges {:llm :process :process :llm}
                             :caps  {:main {:entry :llm}}})]
      (is (thrown-with-msg? Exception #"max turns"
                            (run-agent! g {:content "start"}))))))

;;  Fan-out node

(deftest fan-out-test
  (testing "fan-out dispatches to branches in parallel and collects results"
    (let [g (aya/make-agent {
                             :nodes {:fan       {:type     :fan-out
                                                 :branches [:sentiment :toxicity]}
                                     :sentiment (fn [_ _] {:result {:score 0.8 :label :positive}})
                                     :toxicity  (fn [_ _] {:result {:score 0.1 :label :safe}})
                                     :aggregate (fn [input _]
                                                  {:result {:sentiment (get-in input [:results :sentiment :label])
                                                            :toxicity  (get-in input [:results :toxicity :label])}})}
                             :edges {:fan :aggregate}
                             :caps  {:main {:entry :fan}}})]
      (is (= {:sentiment :positive :toxicity :safe}
             (run-agent! g {:text "hello"}))))))

(deftest fan-out-branch-error-test
  (testing "fan-out with collect-all captures errors per branch"
    (let [g (aya/make-agent {
                             :nodes {:fan   {:type     :fan-out
                                             :branches [:ok :fail]
                                             :strategy :collect-all}
                                     :ok    (fn [_ _] {:result {:v 1}})
                                     :fail  (fn [_ _] (throw (ex-info "branch failed" {})))
                                     :check (fn [input _]
                                              {:result {:ok?     (contains? (:results input) :ok)
                                                        :failed? (contains? (:errors input) :fail)}})}
                             :edges {:fan :check}
                             :caps  {:main {:entry :fan}}})]
      (is (= {:ok? true :failed? true}
             (run-agent! g {}))))))

;;  Dep + wiring (inter-agent)

(deftest dep-wiring-test
  (testing "dep is resolved via wiring at start time"
    (let [doubler (aya/make-agent {
                                   :nodes {:dbl (fn [input _] {:result {:doubled (* 2 (:n input))}})}
                                   :edges {}
                                   :caps  {:main {:entry :dbl}}})
          caller  (aya/make-agent {
                                   :nodes {:prep (fn [input _] {:result {:n (inc (:v input))}})}
                                   :edges {:prep :double}
                                   :deps  [:double]
                                   :caps  {:main {:entry :prep}}})
          sys (-> (aya/make-system {:agents {:caller caller :doubler doubler}
                                    :wiring {:caller {:double [:doubler :main]}}})
                  aya/start!)]
      (is (= {:doubled 12}
             (deref! (aya/run sys :caller :main {:v 5}))))
      (aya/stop! sys))))

(deftest unresolved-dep-test
  (testing "missing wiring for dep throws at runtime"
    (let [agent (aya/make-agent {
                                 :nodes {:a (fn [_ _] {:result :ok})}
                                 :edges {:a :missing}
                                 :deps  [:missing]
                                 :caps  {:main {:entry :a}}})
          sys (-> (aya/make-system {:agents {:test agent}})
                  aya/start!)]
      (is (thrown-with-msg? Exception #"Unresolved dep"
                            (deref! (aya/run sys :test :main {}))))
      (aya/stop! sys))))

(deftest rewire-test
  (testing "rewire! changes dep target at runtime"
    (let [doubler  (aya/make-agent {
                                    :nodes {:dbl (fn [input _] {:result {:result (* 2 (:n input))}})}
                                    :edges {}
                                    :caps  {:main {:entry :dbl}}})
          tripler  (aya/make-agent {
                                    :nodes {:tri (fn [input _] {:result {:result (* 3 (:n input))}})}
                                    :edges {}
                                    :caps  {:main {:entry :tri}}})
          caller (aya/make-agent {
                                  :nodes {:prep (fn [input _] {:result {:n (:v input)}})}
                                  :edges {:prep :compute}
                                  :deps  [:compute]
                                  :caps  {:main {:entry :prep}}})
          sys (-> (aya/make-system {:agents {:caller  caller
                                             :doubler doubler
                                             :tripler tripler}
                                    :wiring {:caller {:compute [:doubler :main]}}})
                  aya/start!)]
      (is (= {:result 10} (deref! (aya/run sys :caller :main {:v 5}))))
      (aya/rewire! sys :caller {:compute [:tripler :main]})
      (is (= {:result 15} (deref! (aya/run sys :caller :main {:v 5}))))
      (aya/stop! sys))))

;;  System lifecycle

(deftest system-lifecycle-test
  (testing "system starts and stops"
    (let [g   (aya/make-agent {
                               :nodes {:echo (fn [input _] {:result {:echoed input}})}
                               :edges {}
                               :caps  {:main {:entry :echo}}})
          sys (-> (aya/make-system {:agents {:echo g}})
                  aya/start!)]
      (is (= {:echoed {:msg "hi"}}
             (deref! (aya/run sys :echo :main {:msg "hi"}))))
      (aya/stop! sys)
      (is (thrown-with-msg? Exception #"not started"
                            (deref! (aya/run sys :echo :main {:msg "hi"})))))))

;;  Named caps (multiple endpoints)

(deftest multi-cap-test
  (testing "same agent accessible via different caps"
    (let [g   (aya/make-agent {
                               :nodes {:greet (fn [input _] {:result {:greeting (str "Hello, " (:name input))}})
                                       :shout (fn [input _] {:result {:greeting (str "HEY " (str/upper-case (:name input)) "!")}})}
                               :edges {}
                               :caps  {:greet {:entry :greet}
                                       :shout {:entry :shout}}})
          sys (-> (aya/make-system {:agents {:bot g}}) aya/start!)]
      (is (= {:greeting "Hello, Alice"}
             (deref! (aya/run sys :bot :greet {:name "Alice"}))))
      (is (= {:greeting "HEY BOB!"}
             (deref! (aya/run sys :bot :shout {:name "Bob"}))))
      (aya/stop! sys))))

;;  Schema validation

(deftest cap-schema-validation-test
  (testing "input validation rejects invalid data"
    (let [g (aya/make-agent {
                             :nodes {:echo (fn [input _] {:result input})}
                             :edges {}
                             :caps  {:main {:entry :echo
                                            :input [:map [:name :string]]}}})]
      (is (thrown-with-msg? Exception #"input validation failed"
                            (run-agent! g {:wrong "key"})))
      (is (= {:name "Alice"} (run-agent! g {:name "Alice"})))))
  (testing "output validation rejects invalid data"
    (let [g (aya/make-agent {
                             :nodes {:bad (fn [_ _] {:result {:wrong "shape"}})}
                             :edges {}
                             :caps  {:main {:entry  :bad
                                            :output [:map [:name :string]]}}})]
      (is (thrown-with-msg? Exception #"output validation failed"
                            (run-agent! g {}))))))

;;  Trace context

(deftest trace-propagation-across-agents-test
  (testing "trace-id propagates through dep wiring, span-id and path change per agent"
    (let [events (atom [])
          mw (reify mw/IGraphMiddleware
               (on-graph-start [_ ctx]
                 (swap! events conj (select-keys ctx [:trace-id :span-id :parent-span-id :path :agent])))
               (on-graph-end   [_ _])
               (on-graph-error [_ _])
               (on-node-start  [_ _])
               (on-node-end    [_ _]))
          doubler (aya/make-agent {
                                   :nodes {:dbl (fn [input _] {:result {:doubled (* 2 (:n input))}})}
                                   :edges {}
                                   :caps  {:main {:entry :dbl}}})
          caller  (aya/make-agent {
                                   :nodes {:prep (fn [input _] {:result {:n (inc (:v input))}})}
                                   :edges {:prep :compute}
                                   :deps  [:compute]
                                   :caps  {:main {:entry :prep}}})
          sys (-> (aya/make-system {:agents {:caller caller :doubler doubler}
                                    :middleware [mw]
                                    :wiring {:caller {:compute [:doubler :main]}}})
                  aya/start!)]
      (deref! (aya/run sys :caller :main {:v 5}))
      (aya/stop! sys)
      (let [caller-evt  (first (filter #(= :caller (:agent %)) @events))
            doubler-evt (first (filter #(= :doubler (:agent %)) @events))]
        (is (= (:trace-id caller-evt) (:trace-id doubler-evt)))
        (is (not= (:span-id caller-evt) (:span-id doubler-evt)))
        (is (nil? (:parent-span-id caller-evt)))
        (is (= (:span-id caller-evt) (:parent-span-id doubler-evt)))
        (is (= [:caller] (:path caller-evt)))
        (is (= [:caller :doubler] (:path doubler-evt)))))))

;;  Inter-agent calls via deps/wiring

(deftest deps-wiring-test
  (testing "deps/wiring delegates to another agent and returns result"
    (let [inner (aya/make-agent {:nodes {:dbl (fn [input _] {:result {:doubled (* 2 (:n input))}})}
                                 :edges {}
                                 :caps  {:main {:entry :dbl}}})
          outer (aya/make-agent {:nodes {:prep   (fn [input _] {:result {:n (:v input)}})
                                         :format (fn [input _] {:result {:answer (:doubled input)}})}
                                 :edges {:prep :compute :compute :format}
                                 :deps  [:compute]
                                 :caps  {:main {:entry :prep}}})
          sys (-> (aya/make-system {:agents {:inner inner :outer outer}
                                    :wiring {:outer {:compute [:inner :main]}}})
                  aya/start!)]
      (is (= {:answer 10} (async/<!! (aya/run sys :outer :main {:v 5}))))
      (aya/stop! sys))))

(deftest deps-wiring-stateful-test
  (testing "stateful agent via deps maintains state across calls"
    (let [inner (aya/make-agent {:nodes     {:counter (fn [_ state]
                                                        (let [n (inc (:count state 0))]
                                                          {:result {:count n}
                                                           :state  {:count n}}))
                                             :check   (fn [input _]
                                                        {:result (if (>= (:count input) 2)
                                                                   {:route :done :data {:total (:count input)}}
                                                                   {:route :again :data input})})}
                                 :edges     {:counter :check
                                             :check   {:again :counter :done :ayatori/done}}
                                 :caps      {:main {:entry :counter}}
                                 :lifecycle {:on-start (fn [_] {:count 0})}
                                 :max-steps 20})
          outer (aya/make-agent {:nodes {:start (fn [input _] {:result input})}
                                 :edges {:start :run-inner}
                                 :deps  [:run-inner]
                                 :caps  {:main {:entry :start}}})
          sys (-> (aya/make-system {:agents {:inner inner :outer outer}
                                    :wiring {:outer {:run-inner [:inner :main]}}})
                  aya/start!)]
      (is (= {:total 2} (async/<!! (aya/run sys :outer :main {}))))
      (aya/stop! sys))))

(deftest deps-wiring-error-propagation-test
  (testing "error in dep agent propagates to caller"
    (let [inner (aya/make-agent {:nodes {:boom (fn [_ _] (throw (ex-info "inner boom" {})))}
                                 :edges {}
                                 :caps  {:main {:entry :boom}}})
          outer (aya/make-agent {:nodes {:start (fn [input _] {:result input})}
                                 :edges {:start :run}
                                 :deps  [:run]
                                 :caps  {:main {:entry :start}}})
          sys (-> (aya/make-system {:agents {:inner inner :outer outer}
                                    :wiring {:outer {:run [:inner :main]}}})
                  aya/start!)
          result (async/<!! (aya/run sys :outer :main {}))]
      (is (instance? Throwable result))
      (is (re-find #"inner boom" (ex-message result)))
      (aya/stop! sys))))
