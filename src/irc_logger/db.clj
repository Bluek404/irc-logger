(ns irc-logger.db
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]))

(def db-spec
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "database.db"})

(defn encode-channel-name [channel-name]
  (string/replace channel-name #"\W"
                  #(str "_" (Long/toString (int (first %)) 36) "_")))

(defn encode-server-name [server-name]
  (string/replace server-name "." "_"))

(defn gen-table-name [server-name channel-name]
  (str (encode-server-name server-name) "__" (encode-channel-name channel-name)))

(defn init-db [config]
  (doseq [server config]
    (let [server-name (encode-server-name (server :host))]
      (doseq [channel-name (server :channels)]
        ; 每个频道单独一张表，表名使用自定义方法编码
        ; 例子:
        ; 服务器: irc.freenode.net 频道: #42!
        ; 编码后: irc_freenode_net___z_42_x_
        ; 解码时先找到第一个 "__" 分割服务器和频道
        ; 然后把服务器里的 "_" 替换为 "."
        ; 频道里的 "_*_" 中的 * 由 36 进制转为 10 进制 code point
        ; 然后转为字符串
        (let [table-name (str server-name "__" (encode-channel-name channel-name))]
          (jdbc/execute! db-spec [(str "CREATE TABLE IF NOT EXISTS " table-name "(
                                        time    TIMESTAMP NOT NULL DEFAULT (DATETIME('now')),
                                        command VARCHAR   NOT NULL,
                                        user    VARCHAR   NOT NULL,
                                        nick    VARCHAR   NOT NULL,
                                        host    VARCHAR   NOT NULL,
                                        text    VARCHAR   NOT NULL)")]))))))

(defn insert-log [irc-server channel data]
  (jdbc/insert! db-spec
                (gen-table-name irc-server channel)
                data))

(defn query-log
  ([irc-server channel page]
   (query-log irc-server channel 100 (when page
                                       (* (dec page) 100))))
  ([irc-server channel limit offset]
   (if offset
     (jdbc/query db-spec (format "SELECT * FROM %s ORDER BY time LIMIT %s OFFSET %s"
                                 (gen-table-name irc-server channel) limit offset))
     (reverse (jdbc/query db-spec (format "SELECT * FROM %s ORDER BY time DESC LIMIT %s"
                                          (gen-table-name irc-server channel) limit))))))
