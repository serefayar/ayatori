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
  (let [g (aya/make-agent {:nodes {:parse  (fn [input _] {:result {:text (str "parsed:" (:raw input))}})
                                   :enrich (fn [input _] {:result {:text (str "enriched:" (:text input))}})
                                   :score  (fn [input _] {:result {:score 0.95 :text (:text input)}})}
                           :edges {:parse :enrich :enrich :score}
                           :caps  {:main {:entry :parse}}})]
    (is (= {:score 0.95 :text "enriched:parsed:hello"}
           (run-agent! g {:raw "hello"})))))

(deftest conditional-branch-test
  (let [g (aya/make-agent {:nodes {:score   (fn [input _]
                                              {:result (if (> (:confidence input) 0.8)
                                                         {:route :approve :data {:decision :approved}}
                                                         {:route :reject :data {:decision :rejected}})})
                                   :approve (fn [input _] {:result {:result :approved :input input}})
                                   :reject  (fn [input _] {:result {:result :rejected :input input}})}
                           :edges {:score {:approve :approve :reject :reject}}
                           :caps  {:main {:entry :score}}})]
    (is (= {:result :approved :input {:decision :approved}}
           (run-agent! g {:confidence 0.9})))
    (is (= {:result :rejected :input {:decision :rejected}}
           (run-agent! g {:confidence 0.5})))))

(deftest cycle-with-state-test
  (let [g (aya/make-agent {:nodes     {:counter (fn [_ state]
                                                   (let [n (inc (:count state 0))]
                                                     {:result {:count n} :state {:count n}}))
                                       :check   (fn [input _]
                                                  {:result (if (>= (:count input) 3)
                                                             {:route :done :data {:final (:count input)}}
                                                             {:route :again :data input})})}
                           :edges     {:counter :check
                                       :check   {:again :counter :done :ayatori/done}}
                           :caps      {:main {:entry :counter}}
                           :lifecycle {:on-start (fn [_] {:count 0})}
                           :max-steps 20})]
    (is (= {:final 3} (run-agent! g {})))))

(deftest error-propagation-test
  (let [g (aya/make-agent {:nodes {:a (fn [_ _] (throw (ex-info "boom" {})))
                                   :b (fn [input _] {:result input})}
                           :edges {:a :b}
                           :caps  {:main {:entry :a}}})]
    (is (thrown-with-msg? Exception #"boom" (run-agent! g {})))))

(defn- mock-invoke [responses]
  (let [idx (atom 0)]
    (fn [_client _params]
      (let [i @idx
            _ (swap! idx inc)]
        (doto (async/promise-chan) (async/put! (nth responses i)))))))

(deftest llm-text-response-test
  (let [invoke-fn (mock-invoke [{:role :assistant :content "Hello!"}])
        g (aya/make-agent {:nodes {:llm    {:type :llm :invoke-fn invoke-fn :prompt "You are helpful"}
                                   :output (fn [input _] {:result {:answer (:content input)}})}
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
                                   :search (fn [input _] {:result (str "results for " (:query input))})
                                   :output (fn [input _] {:result {:answer (:content input)}})}
                           :edges {:llm {:done :output :search :search} :search :llm}
                           :caps  {:main {:entry :llm}}})]
    (is (= {:answer "Found results"} (run-agent! g {:content "search clojure"})))))

(deftest fan-out-test
  (let [g (aya/make-agent {:nodes {:fan       {:type :fan-out :branches [:sentiment :toxicity]}
                                   :sentiment (fn [_ _] {:result {:label :positive}})
                                   :toxicity  (fn [_ _] {:result {:label :safe}})
                                   :aggregate (fn [input _]
                                                {:result {:sentiment (get-in input [:results :sentiment :label])
                                                          :toxicity  (get-in input [:results :toxicity :label])}})}
                           :edges {:fan :aggregate}
                           :caps  {:main {:entry :fan}}})]
    (is (= {:sentiment :positive :toxicity :safe} (run-agent! g {:text "hello"})))))

(deftest dep-wiring-test
  (let [doubler (aya/make-agent {:nodes {:dbl (fn [input _] {:result {:doubled (* 2 (:n input))}})}
                                 :edges {}
                                 :caps  {:main {:entry :dbl}}})
        caller  (aya/make-agent {:nodes {:prep (fn [input _] {:result {:n (inc (:v input))}})}
                                 :edges {:prep :double}
                                 :deps  [:double]
                                 :caps  {:main {:entry :prep}}})
        sys (-> (aya/make-system {:agents {:caller caller :doubler doubler}
                                  :wiring {:caller {:double [:doubler :main]}}})
                aya/start!)]
    (is (= {:doubled 12} (deref! (aya/run sys :caller :main {:v 5}))))
    (aya/stop! sys)))

(deftest system-lifecycle-test
  (let [g   (aya/make-agent {:nodes {:echo (fn [input _] {:result {:echoed input}})}
                             :edges {}
                             :caps  {:main {:entry :echo}}})
        sys (-> (aya/make-system {:agents {:echo g}}) aya/start!)]
    (is (= {:echoed {:msg "hi"}} (deref! (aya/run sys :echo :main {:msg "hi"}))))
    (aya/stop! sys)
    (is (thrown-with-msg? Exception #"not started"
                          (deref! (aya/run sys :echo :main {:msg "hi"}))))))

(deftest cap-schema-validation-test
  (let [g (aya/make-agent {:nodes {:echo (fn [input _] {:result input})}
                           :edges {}
                           :caps  {:main {:entry :echo :input [:map [:name :string]]}}})]
    (is (thrown-with-msg? Exception #"input validation" (run-agent! g {:wrong "key"})))
    (is (= {:name "Alice"} (run-agent! g {:name "Alice"})))))
