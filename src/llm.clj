(ns llm
  (:require  [clojure.string :as str]
             [agents.common :refer [query-ollama]]
             [agents.search :refer [process-search-agent]]))

(defn default-prompt [query]
  (str "You are a helpful assistant. "
       "Answer the question based on the context provided. "
       "If you don't know the answer, say 'I don't know'. "
       "Question: " query))


(defn process-query [{:keys [agent query]}]
  (cond
    (= agent :search)
    (with-out-str (process-search-agent query))

    (= agent :default)
    (let [prompt (default-prompt query)
          response (query-ollama prompt)]
      (if (str/blank? response)
        "I don't know."
        response))

    :else
    (str "❌ Unsupported agent: " agent)))
