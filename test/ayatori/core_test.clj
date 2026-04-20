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
                                              {:result (if (> (:confidence input) 0.8)
                                                         {:route :approve :data {:decision :approved}}
                                                         {:route :reject :data {:decision :rejected}})})
                                   :approve (fn [input] {:result {:result :approved :input input}})
                                   :reject  (fn [input] {:result {:result :rejected :input input}})}
                           :edges {:score {:approve :approve :reject :reject}}
                           :caps  {:main {:entry :score}}})]
    (is (= {:result :approved :input {:decision :approved}}
           (run-agent! g {:confidence 0.9})))
    (is (= {:result :rejected :input {:decision :rejected}}
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
                           :edges {:llm {:done :output}}
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
                           :edges {:llm {:done :output :search :search} :search :llm}
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
                             :edges {:llm {:search :search}
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

