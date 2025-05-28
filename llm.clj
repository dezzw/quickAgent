(ns llm
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn query-ollama [prompt]
  (let [resp (http/post "http://localhost:11434/api/generate"
                        {:headers {"Content-Type" "application/json"}
                         :body (json/generate-string
                                {:model "llama3.2"
                                 :prompt prompt
                                 :stream false})})
        body (:body resp)]
    (-> body (json/parse-string true) :response)))


(defn default-prompt [query]
  (str "You are a helpful assistant. "
       "Answer the question based on the context provided. "
       "If you don't know the answer, say 'I don't know'. "
       "Question: " query))


(defn process-query [query]
  (let [prompt (default-prompt query)
        response (query-ollama prompt)]
    (if (str/blank? response)
      "I don't know."
      response)))
