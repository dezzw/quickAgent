#!/usr/bin/env bb

(ns agents.search
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [hickory.core :as h]
            [hickory.select :as s]
            [llm :refer [query-ollama]]))

(defn fetch-suggestions [query]
  (let [resp (http/get "https://duckduckgo.com/ac/"
                       {:query-params {"q" query}
                        :headers {"User-Agent" "Mozilla/5.0"}})
        body (:body resp)
        suggestions (json/parse-string body true)]
    (->> suggestions
         (map :phrase)
         (take 5))))

(defn fetch-ddg-results [query]
  (let [url "https://html.duckduckgo.com/html/"
        params {"q" query}
        headers {"User-Agent" "Mozilla/5.0"}
        response (http/post url {:form-params params :headers headers})]

    ;; (println "\n--- Raw HTML length:" (count (:body response))) ;; ✅ 确认响应是否返回

    (let [html (:body response)
          dom (-> html h/parse h/as-hickory)]

      ;; (println "\n--- Parsed DOM:" (take 1 dom)) ;; ✅ 检查 DOM 结构

      (let [results (s/select (s/class "result__body") dom)]

        ;; (println "\n--- Matched result__body nodes:" (count results)) ;; ✅ CSS Selector 命中数

        (->> results
             (map (fn [node]
                    (let [a-node (first (s/select (s/class "result__a") node))
                          title  (some-> a-node :content first)
                          url    (some-> a-node :attrs :href)]
                      ;; (println "--- Extracted:" {:title title :url url})
                      (when (and title url)
                        {:title title :url url}))))
             (remove nil?)
             (take 3))))))

(defn extract-text-from-html [html]
  (let [dom (-> html h/parse h/as-hickory)
        nodes (s/select (s/or
                         (s/tag "p")
                         (s/tag "section")
                         (s/tag "div")
                         (s/tag "main")
                         (s/tag "article")) dom)
        text (->> nodes
                  (mapcat :content)
                  (filter string?)
                  (str/join " ")
                  (str/trim))]
    (subs text 0 (min 2000 (count text)))))

(defn fetch-page-content [url]
  (try
    (let [{:keys [body status]} (http/get url {:headers {"User-Agent" "Mozilla/5.0"}})]
      (if (= status 200)
        (try
          (extract-text-from-html body)
          (catch Exception e
            (str "提取失败: " (.getMessage e))))
        (str "抓取失败，状态码: " status)))
    (catch Exception e
      (str "请求异常: " (.getMessage e)))))

(defn build-prompt [sugg title url content]
  (str
   "你是一个中文信息助手，请阅读以下网页内容并总结其是否对搜索关键词「" sugg "」有帮助。\n"
   "网页标题：" title "\n"
   "网址：" url "\n"
   "正文内容如下：\n"
   content "\n\n"
   "请用简短的中文总结这个网页是否相关，以及是否值得阅读。若相关，请简述其主要信息；若不相关，请说明原因。"))

(defn process-query [query]
  (println ">> 原始查询：" query)
  (doseq [sugg (fetch-suggestions query)]
    (println "\n=== 建议关键词：" sugg)
    (doseq [{:keys [title url]} (fetch-ddg-results sugg)]
      (println "\n---")
      (println "标题：" title)
      (println "链接：" url)
      (try
        (let [content (fetch-page-content url)]
          (if (or (nil? content) (str/blank? content))
            (println "⚠️ 网页内容为空，跳过")
            (let [prompt  (build-prompt sugg title url content)
                  summary (query-ollama prompt)]
              (println "🧠 LLM 总结：\n" summary))))
        (catch Exception e
          (println "❌ 处理失败：" (.getMessage e)))))))

;; 执行入口
(let [[query] *command-line-args*]
  (if query
    (process-query query)
    (println "Usage: search.clj \"your query here\"")))
