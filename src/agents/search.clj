(ns agents.search
  (:require [org.httpkit.client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]
            [hickory.core :as h]
            [hickory.select :as s]
            [agents.common :refer [query-ollama]]))

(defn fetch-suggestions [query]
  (let [{:keys [body status error]}
        @(http/get "https://duckduckgo.com/ac/"
                   {:query-params {"q" query}
                    :headers {"User-Agent" "Mozilla/5.0"}})]
    (if (or error (not= status 200))
      (do (println "⚠️ 查询 DuckDuckGo 建议失败：" error status) [])
      (->> (json/parse-string body true)
           (map :phrase)
           (take 5)))))

(defn fetch-ddg-results [query]
  (let [url "https://html.duckduckgo.com/html/"
        params {"q" query}
        headers {"User-Agent" "Mozilla/5.0"}
        {:keys [body status error]}
        @(http/post url {:form-params params :headers headers})]
    (if (or error (not= status 200))
      (do (println "⚠️ 查询 DuckDuckGo 结果失败：" error status) [])
      (let [html body
            dom (-> html h/parse h/as-hickory)
            results (s/select (s/class "result__body") dom)]
        (->> results
             (map (fn [node]
                    (let [a-node (first (s/select (s/class "result__a") node))
                          title  (some-> a-node :content first)
                          url    (some-> a-node :attrs :href)]
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
    (let [{:keys [body status error]}
          @(http/get url {:headers {"User-Agent" "Mozilla/5.0"}})]
      (cond
        error (str "请求异常: " error)
        (not= status 200) (str "抓取失败，状态码: " status)
        :else (try
                (extract-text-from-html body)
                (catch Exception e
                  (str "提取失败: " (.getMessage e))))))
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

(defn process-search-agent [query]
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

;; 入口
(defn -main [& args]
  (let [query (first args)]
    (if query
      (process-search-agent query)
      (println "Usage: clj -M -m agents.search \"your query here\""))))
