(ns irc-logger.core
  (:require [clojure.pprint :as pprint])
  (:require [clojure.string :as string])
  (:require [clojure.data.json :as json])
  (:require [clojure.core.async :as async :refer [<!! >!! chan]])
  (:require [clojure.java.jdbc :as jdbc])
  (:require [irclj.core :as irc])
  (:gen-class))

(def exit (chan))

(def db
  {:classname   "org.sqlite.JDBC"
   :subprotocol "sqlite"
   :subname     "database.db"})

(defn encode-channel-name [channel-name]
  (string/replace channel-name #"\W"
                  #(str "_" (Long/toString (int(first (seq %))) 36) "_")))

(defn encode-server-name [server-name]
  (string/replace server-name "." "_"))

(defn gen-table-name [server-name channel-name]
  (str (encode-server-name server-name) "__" (encode-channel-name channel-name)))

(defn log-join [connection args]
  (jdbc/insert! db
                (gen-table-name (@connection :network) (first (args :params)))
                {:command "JOIN"
                 :user    (args :user)
                 :nick    (args :nick)
                 :host    (args :host)
                 :text    "加入了聊天室"}))

(defn log-kick [connection args]
  (let [params (args :params)]
    (jdbc/insert! db
                  (gen-table-name (@connection :network) (first params))
                  {:command "KICK"
                   :user    (args :user)
                   :nick    (args :nick)
                   :host    (args :host)
                   :text    (str "将 " (second params) " 踢出了聊天室"
                                 ; 如果有理由则附加上理由
                                 (if (= (count params) 3)
                                   (str "(" (last params) ")")
                                   ""))})))

(defn log-nick [connection args]
  (let [server   (@connection :network)
        new-nick (first (args :params))
        data     {:command "NICK"
                  :user    (args :user)
                  :nick    (args :nick)
                  :host    (args :host)
                  :text    (str "改名为: " new-nick)}
        ; 找出这个用户所在的所有频道
        ; 因为已经改名了，所以使用新的名字查找
        channels (for [[k v] (@connection :channels)
                       :when (contains? (v :users) new-nick)]
                   k)]
    (doseq [channel channels]
      (jdbc/insert! db
                    (gen-table-name server channel)
                    data))))

(defn log-part [connection args]
  (let [params (args :params)]
    (jdbc/insert! db
                  (gen-table-name (@connection :network) (first params))
                  {:command "PART"
                   :user    (args :user)
                   :nick    (args :nick)
                   :host    (args :host)
                   :text    (str "离开了聊天室" (if (= (count params) 2)
                                                  (str "(" (second params) ")")
                                                  ""))})))

(defn log-quit [connection args]
  (let [server (@connection :network)
        params (args :params)
        data   {:command "QUIT"
                :user    (args :user)
                :nick    (args :nick)
                :host    (args :host)
                :text    (str "断开了链接" (if (= (count params) 1)
                                             (str "(" (first params) ")")
                                             ""))}]
    (doseq [channel (args :channels)]
      (jdbc/insert! db
                    (gen-table-name server channel)
                    data))))

(defn log-mode [connection args]
  (let [params (args :params)]
    (when (= (count params) 3)
      (let [[channel mode target] params]
        (jdbc/insert! db
                      (gen-table-name (@connection :network) channel)
                      {:command "MODE"
                       :user    (args :user)
                       :nick    (args :nick)
                       :host    (args :host)
                       :text    (str "将 " target " 的模式设定为: " mode)})))))

(defn callback [connection args]
  (jdbc/insert! db
                (gen-table-name (@connection :network) (args :target))
                (select-keys args [:command :user :nick :host :text])))

(defn do-server [server]
  (pprint/pprint server)
  (let [connection (irc/connect (server :host) (server :port) (server :nick)
                                :ssl? (server :ssl) :reconnect? true
                                :real-name (server :real-name)
                                :callbacks {:nick log-nick
                                            :join log-join
                                            :part log-part
                                            :quit log-quit
                                            :kick log-kick
                                            :mode log-mode
                                            :privmsg callback})]
    (doseq [channel (server :channels)]
      (irc/join connection channel))))

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
        ; 频道里的 "_*_" 中的 * 由 36 进制转为 10 进制
        ; 然后转为字符串
        (let [table-name (str server-name "__" (encode-channel-name channel-name))]
          (jdbc/execute! db [(str
                               "CREATE TABLE IF NOT EXISTS " table-name "(
                                time    TIMESTAMP NOT NULL DEFAULT (DATETIME('now')),
                                command VARCHAR   NOT NULL,
                                user    VARCHAR   NOT NULL,
                                nick    VARCHAR   NOT NULL,
                                host    VARCHAR   NOT NULL,
                                text    VARCHAR   NOT NULL)")]))))))

(defn -main
  ([] (-main "./config.json"))
  ([config-file]
   (let [config (json/read-str (slurp config-file) :key-fn keyword)]
     (init-db config)
     (doseq [server config]
       (do-server server)))
   (<!! exit)))
