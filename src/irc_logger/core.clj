(ns irc-logger.core
  (:require [clojure.pprint :as pprint]
            [clojure.data.json :as json]
            [clojure.core.async :as async :refer [go <!! >!! chan]]
            [irclj.core :as irc]
            [irc-logger.http :as http]
            [irc-logger.db :as db])
  (:gen-class))

(defn log-join [connection args]
  (db/insert-log (@connection :network) (first (args :params))
                {:command "JOIN"
                 :user    (args :user)
                 :nick    (args :nick)
                 :host    (args :host)
                 :text    "加入了频道"}))

(defn log-kick [connection args]
  (let [params (args :params)]
    (db/insert-log (@connection :network) (first params)
                   {:command "KICK"
                    :user    (args :user)
                    :nick    (args :nick)
                    :host    (args :host)
                    :text    (str "将 " (second params) " 踢出了频道"
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
                  :text    (str "改名为: " new-nick)}]
    (doseq [channel (args :channels)]
      (db/insert-log server channel data))))

(defn log-part [connection args]
  (let [params (args :params)]
    (db/insert-log (@connection :network) (first params)
                   {:command "PART"
                    :user    (args :user)
                    :nick    (args :nick)
                    :host    (args :host)
                    :text    (str "离开了频道" (if (= (count params) 2)
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
      (db/insert-log server channel data))))

(defn log-mode [connection args]
  (let [params (args :params)]
    (when (= (count params) 3)
      (let [[channel mode target] params]
        (db/insert-log (@connection :network) channel
                       {:command "MODE"
                        :user    (args :user)
                        :nick    (args :nick)
                        :host    (args :host)
                        :text    (str "将 " target " 的模式设定为: " mode)})))))

(defn callback [connection args]
  (db/insert-log (@connection :network) (args :target)
                 (select-keys args [:command :user :nick :host :text])))

(defn do-server [server]
  (pprint/pprint server)
  (try
    (let [connection (irc/connect (server :host) (server :port) (server :nick)
                                  :ssl? (server :ssl) :timeout (* (server :timeout) 1000)
                                  :real-name (server :real-name)
                                  :callbacks {:nick log-nick
                                              :join log-join
                                              :part log-part
                                              :quit log-quit
                                              :kick log-kick
                                              :mode log-mode
                                              :privmsg callback
                                              :on-shutdown (fn [connection]
                                                             (irc/kill connection)
                                                             (go (do-server server)))
                                              :433 (fn [connection args]
                                                     (irc/set-nick connection (str (second (args :params)) \_)))
                                              :raw-log (fn [a b c]
                                                         (println (.toString (new java.util.Date)))
                                                         (irclj.events/stdout-callback a b c))})]
      (doseq [channel (server :channels)]
        (irc/join connection channel)))
    (catch Exception e
      (prn e)
      (go (do (do-server server))))))

(defn -main
  ([] (-main "./config.json"))
  ([config-file]
   (let [config (json/read-str (slurp config-file) :key-fn keyword)]
     (db/init-db config)
     (go (http/start-server config))
     (doseq [server config]
       (do-server server))
     (<!! (chan)))))
