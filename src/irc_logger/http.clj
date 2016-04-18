(ns irc-logger.http
  (:require [compojure.route :as route]
            [compojure.handler :refer [site]]
            [hiccup.core :refer [html]]
            [compojure.core :refer [defroutes GET POST context]]
            [org.httpkit.server :refer [run-server]]
            [clojure.string :as string]
            [irc-logger.db :as db])
  (:import [java.lang StringBuilder]))

(def irc-list (atom []))

(defn frame [title body]
  (html [:html
         [:head
          [:title title]
          [:link {:rel "stylesheet" :href "/css/styles.css"}]]
         [:body body]]))

(defn index [req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (frame "INDEX"
                (map (fn [l]
                       [:div [:h1 (l :host)]
                        [:ul
                         (map (fn [c]
                                [:li
                                 [:a {:href (str "/log/" (l :host) \/ (apply str (next c)))}
                                  c]])
                              (l :channels))]])
                     @irc-list))})

(defn find-first [f coll]
  (loop [l coll]
    (when-not (empty? l)
      (if-let [result (f (first l))]
        result
        (recur (next l))))))

(defn log [req]
  (let [params (req :params)
        server (params :server)
        page (when (params :page)
               (Integer. (params :page)))
        channel (str \# (params :channel))]
    (prn params)
    (when-let [irc (find-first #(when (= (get % :host) server) %) @irc-list)]
      (when (some #(= % channel) (irc :channels))
        (let [rows (db/query-log server channel page)]
          (when-not (empty? rows)
            {:status 200
             :headers {"Content-Type" "text/html; charset=utf-8"}
             :body (frame channel
                          [:div.log
                           (map (fn [row]
                                  [:div {:class (row :command)}
                                   [:time (row :time)]
                                   [:a.user {:title (row :user)} (row :nick)]
                                   [:a.text (row :text)]])
                                rows)])}))))))

(defn encode-log [log]
  (let [builder (StringBuilder.)]
    (doseq [l log]
      (.append builder (format "%s %s %s %s %s %s\n"
                               (l :time)
                               (l :command)
                               (l :host)
                               (l :user)
                               (l :nick)
                               (l :text))))
    (.toString builder)))

(defn api [req]
  (let [params (req :params)
        server (params :server)
        channel (str \# (params :channel))
        limit (Integer. (params :limit))
        offset (when (params :offset)
                 (Integer. (params :offset)))]
    (when-let [irc (find-first #(when (= (get % :host) server) %) @irc-list)]
      (when (some #(= % channel) (irc :channels))
        (if (<= limit 1000)
          {:status 200
           :headers {"Content-Type" "application/json; charset=utf-8"}
           :body (encode-log (db/query-log server channel limit offset))}
          {:status 403
           :headers {"Content-Type" "text/plain; charset=utf-8"}
           :body "Max LIMIT = 1000"})))))

(defn search [req]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (frame "SEARCH"
                [:script {:src "/js/script.js"}])})

(defroutes all-routes
  (GET "/" [] index)
  (context "/log/:server/:channel" []
    (GET "/" [] log)
    (GET "/:page{[0-9]+}" [] log))
  (context "/api/:server/:channel/:limit{[0-9]+}" []
    (GET "/" [] api)
    (GET "/:offset{[0-9]+}" [] api))
  (GET "/search" [] search)
  (route/resources "/")
  (route/not-found "<p>Page not found.</p>"))

(defn start-server [config]
  (reset! irc-list config)
  (println "HTTP Server start at http://127.0.0.1:8358")
  (run-server (site #'all-routes) {:port 8358}))
