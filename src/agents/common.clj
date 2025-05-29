(ns agents.common
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]))

(defn query-ollama [prompt]
  (let [{:keys [status headers body error]}
        @(http/post "http://localhost:11434/api/generate"
                    {:headers {"Content-Type" "application/json"}
                     :body (json/generate-string
                            {:model "llama3.2"
                             :prompt prompt
                             :stream false})})]
    (if error
      (throw error)
      (-> body (json/parse-string true) :response))))
