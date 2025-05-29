(ns agents.common
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
