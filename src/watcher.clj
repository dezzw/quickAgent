#!/usr/bin/env bb

(ns watcher
  (:require [honey.sql :as sql]
            [clojure.string :as str]
            [babashka.process :refer [process check]]
            [pod.babashka.go-sqlite3 :as sqlite]
            [llm :refer [process-query]]))

(def db-path (str (System/getProperty "user.home") "/Library/Messages/chat.db"))

(def latest-date-query
  {:select [[[:max :date] :latest_date]]
   :from [:message]
   :where [:is-not :text nil]})

(defn get-latest-message-date []
  (let [result (sqlite/query db-path (sql/format latest-date-query))]
    (-> result first :latest_date)))

(defn fetch-new-messages [since-date]
  (sqlite/query db-path
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
                 since-date]))

(defn print-message [msg]
  (let [sender (if (:is_from_me msg) "Me" (:contact msg))
        group-info (if (:cache_roomnames msg) (str " (Agent: " (:cache_roomnames msg) ")") "")
        text (:text msg)
        date (:message_date msg)]
    (println (format "[%s] %s%s: %s" date sender group-info text))))

(defn send-imessage [to text]
  (let [script-path "scripts/send-message.applescript"
        proc (check (process ["osascript" script-path to text] {:out :string}))]
    (println "✅ iMessage sending status:" (str/trim (:out proc)))))

(defn parse-command [text]
  (when-let [[_ command query] (re-matches #"@(\w+):\s*(.+)" text)]
    {:command command :query query}))

(defn dispatch-query [{:keys [command query] :as parsed}]
  (cond
    (nil? parsed)              (process-query {:agent :default :query (:text parsed)})
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
          result  (dispatch-query (assoc parsed :text text))] ; fallback 用 text
      (reply-message contact result))))

(defn -main []
  (println "👁️ Watching iMessage new message...")
  (loop [last-date (or (get-latest-message-date) 0)]
    (Thread/sleep 1000)
    (let [new-date (get-latest-message-date)]
      (if (and new-date (> new-date last-date))
        (let [messages (fetch-new-messages last-date)]
          (doseq [msg messages]
            (println "📥 Raw message:" msg)
            (print-message msg)
            (handle-message msg)) ; 👈 确保这行有效
          (recur new-date))
        (recur last-date)))))

(-main)
