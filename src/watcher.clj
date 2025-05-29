(ns watcher
  (:require [clojure.string :as str]
            [clojure.java.shell :refer [sh]]
            [honey.sql :as sql]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [llm :refer [process-query]])
  (:import [java.sql DriverManager]))

;; 1. DB 连接配置
(def db-path (str (System/getProperty "user.home") "/Library/Messages/chat.db"))
(def db-spec {:dbtype "sqlite" :dbname db-path})

;; 2. 获取最新消息时间
(def latest-date-query
  {:select [[[:max :date] :latest_date]]
   :from [:message]
   :where [:is-not :text nil]})

(defn get-latest-message-date []
  (let [sql-vec (sql/format latest-date-query)
        ds (jdbc/get-datasource db-spec)]
    (-> (jdbc/execute! ds sql-vec {:builder-fn rs/as-unqualified-lower-maps})
        first
        :latest_date)))

;; 3. 获取新消息
(defn fetch-new-messages [since-date]
  (let [sql
        ["SELECT
            datetime(message.date/1000000000 + strftime('%s', '2001-01-01'), 'unixepoch', 'localtime') AS message_date,
            message.text,
            handle.id as contact,
            message.is_from_me,
            message.cache_roomnames,
            message.date AS original_date
          FROM message
          LEFT JOIN handle ON message.handle_id = handle.ROWID
          WHERE message.text IS NOT NULL AND message.date > ?
          ORDER BY message.date ASC"
         since-date]
        ds (jdbc/get-datasource db-spec)]
    (jdbc/execute! ds sql {:builder-fn rs/as-unqualified-lower-maps})))

;; 4. 打印消息
(defn print-message [msg]
  (let [sender (if (= (:is_from_me msg) 1) "Me" (:contact msg))
        group-info (if (:cache_roomnames msg) (str " (Agent: " (:cache_roomnames msg) ")") "")
        text (:text msg)
        date (:message_date msg)]
    (println (format "[%s] %s%s: %s" date sender group-info text))))

;; 5. 发送 iMessage
(defn send-imessage [to text]
  (let [script-path (str (System/getProperty "user.dir") "/src/scripts/send-message.applescript")
        {:keys [out err exit]} (sh "osascript" script-path to text)]
    (if (zero? exit)
      (println "✅ iMessage sending status:" (str/trim out))
      (println "❌ Error sending iMessage:" err))))

;; 6. 指令解析
(defn parse-command [text]
  (when-let [[_ command query] (re-matches #"@(\w+):\s*(.+)" text)]
    {:command command :query query}))

(defn dispatch-query [{:keys [command query text] :as parsed}]
  (cond
    (nil? parsed)              (process-query {:agent :default :query text})
    (= command "search")       (process-query {:agent :search :query query})
    (= command "translate")    (process-query {:agent :translate :query query})
    :else                      (process-query {:agent :default :query query})))

(defn reply-message [contact result]
  (println "📤 Sending message back to:" contact)
  (doseq [chunk (partition-all 800 (seq result))]
    (send-imessage contact (apply str chunk))))

(defn handle-message [msg]
  (println "👉 handle-message invoked for:" msg)
  (when (= (:is_from_me msg) 0)
    (let [text    (:text msg)
          contact (:contact msg)
          parsed  (parse-command text)
          result  (dispatch-query (assoc parsed :text text))]
      (reply-message contact result))))

(defn -main [& _]
  (println "👁️ Watching iMessage new message...")
  (loop [last-date (or (get-latest-message-date) 0)]
    (Thread/sleep 1000)
    (let [new-date (get-latest-message-date)]
      (if (and new-date (> new-date last-date))
        (let [messages (fetch-new-messages last-date)]
          (doseq [msg messages]
            (println "📥 Raw message:" msg)
            (print-message msg)
            (handle-message msg))
          (recur new-date))
        (recur last-date)))))

;; 只要在 deps.edn 设置 main-opts 或手动 clj -M -m watcher 即可
