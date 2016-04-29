(ns irc-logger.core
  (:require [clojure.string :as string]
            [dommy.core :as dommy :refer-macros [sel sel1]]
            [hiccups.runtime :as hiccupsrt]
            [cljs.core.async :refer [<!]]
            [cljs-http.client :as http])
  (:require-macros [hiccups.core :as hiccups :refer [html]]
                   [cljs.core.async.macros :refer [go]])
  (:import goog.crypt))

(enable-console-print!)

(defn get-color-class [name]
  (as-> name s
    (goog.crypt/stringToUtf8ByteArray s)
    (reduce + s)
    (mod s 18)
    (str "c" s)))

; TODO
(defn parse-integer [text]
  (loop [t text, r ""]
    (if-not (empty? t)
      (let [c (first t)]
        (if (js/isNaN (js/parseInt c))
          (if (not= r "")
            [(js/parseInt r) t]
            [nil t])
          (recur (next t) (str r c))))
      (if (not= r "")
        [(js/parseInt r) t]
        [nil t]))))

(def colors ["white" "black" "blue" "green" "red" "brown"
             "purple" "orange" "yellow" "lime" "teal" "cyan"
             "royal" "pink" "grey" "silver"])

(defn parse-text-color-and-background [text]
  (let [[color next-text] (parse-integer text)
        [background next-text] (if (= (first next-text) \,)
                                 (parse-integer (next next-text))
                                 [nil next-text])
        color (when color (nth colors color nil))
        background (when background (nth colors background nil))]
    [color background next-text]))

(defn parse-text [text]
  (loop [t text, tail "", r ""]
    (if-not (empty? t)
        (case (first t)
          \u0002 (recur (next t) (str "</b>" tail) (str r "<b>"))
          \u0003 (let [[color background next-text] (parse-text-color-and-background (next t))
                       head (str "<a class=\"" color (if background (str " b-" background)) "\">")]
                   (recur next-text (str "</a>" tail) (str r head)))
          \u001d (recur (next t) (str "</i>" tail) (str r "<i>"))
          \u001f (recur (next t) (str "</u>" tail) (str r "<u>"))
          \u0016 (recur (next t) tail r) ; TODO
          \u000f (recur (next t) "" (str r tail))
          (recur (next t) tail (str r (first t))))
        (str r tail))))

(defn parse-url [text]
  (string/replace text
                  #"https?://[\w-\.~!\*'\(\);:@&=+$,\/?%#\[\]]*"
                  #(str "<a href=\"" % "\">" % "</a>")))

(defn parse-image [text]
  (let [image-list (->> text
                        (re-seq #"href=\"(.+\.(?:png|jpg|svg|gif|bmp|jpeg|webp))\"")
                        (map #(second %))
                        (map #(str "<a href=\"" % "\"><img src=\"" % "\"/></a>"))
                        (apply str))]
    (str text "<br/>" image-list)))

(defn append-zero [i]
  (if (< i 10)
    (str \0 i)
    i))

(defn format-time [t]
  (let [year (.getFullYear t)
        month (inc (.getMonth t))
        date (.getDate t)
        hours (.getHours t)
        minutes (.getMinutes t)
        seconds (.getSeconds t)]
    (str year \- (append-zero month) \- (append-zero date) \space
         (append-zero hours) \: (append-zero minutes) \: (append-zero seconds))))

(defn to-local-time [time]
  (let [utc-time (->> (str time " UTC")
                      (new js/Date))]
    (format-time utc-time)))

(defn main-log []
  (let [name-el (sel :.user)
        time-el (sel :time)
        text-el (sel :.text)]
    (doseq [name-e name-el]
      (let [name (dommy/text name-e)]
        (dommy/add-class! name-e (get-color-class name))))
    (prn (.getTimezoneOffset (new js/Date)))
    (doseq [time-e time-el]
      (let [utc-time (dommy/text time-e)
            local-time (to-local-time utc-time)]
        (prn utc-time local-time)
        (dommy/set-text! time-e local-time)))
    (doseq [text-e text-el]
      (let [text (dommy/html text-e)
            parsed (-> text
                       (parse-text)
                       (parse-url)
                       (parse-image))]
        (dommy/set-html! text-e parsed)))))

(defn get-selected-str [e]
  (let [selected (array-seq (.-selectedOptions e))
        elem (first selected)]
    (dommy/text elem)))

(defn find-first [f coll]
  (loop [l coll]
    (when-not (empty? l)
      (if-let [result (f (first l))]
        result
        (recur (next l))))))

(defn encode-uri [text]
  (-> (js/encodeURI text)
      (string/replace #"#" "%23")))

(defn parse-text-log [text]
  (->> text
       (string/split-lines)
       (map #(string/split % #" " 7))
       (map (fn [line] {:time (str (first line) " " (second line))
                        :command (nth line 2)
                        :host (nth line 3)
                        :user (nth line 4)
                        :nick (nth line 5)
                        :text (nth line 6)}))))

(defn html-escape [text]
  (string/escape text {\< "&lt;"
                       \> "&gt;"
                       \& "&amp;"
                       \" "&quot;"}))

(defn parse-log-to-html [log]
  (html (map (fn [row]
               [:div {:class (row :command)}
                [:a {:href (row :link)} [:time (to-local-time (row :time))]]
                [:a {:title (row :user)
                     :class (str "user "(get-color-class (row :nick)))}
                 (row :nick)]
                [:p.text (-> (row :text)
                             (html-escape)
                             (parse-text)
                             (parse-url)
                             (parse-image))]])
             log)))

(defn main-search []
  (let [irc-list (->> (sel1 :#irc-list)
                      (dommy/text)
                      (string/split-lines)
                      (map #(string/split % \space))
                      (map (fn [s] {:host (first s) :channels (next s)})))
        server-select-e (sel1 :#server-select)
        channel-select-e (sel1 :#channel-select)
        search-e (sel1 :#search)
        search-button-e (sel1 :#search-button)
        search-result-e (sel1 :#search-result)
        msg-e (sel1 :#msg)]

    (dommy/set-html! server-select-e (html (map (fn [s] [:option (s :host)]) irc-list)))
    (set! (.-onselect server-select-e) (fn []
                                         (let [server-host (get-selected-str server-select-e)
                                               server (find-first #(when (= (% :host) server-host) %)
                                                                  irc-list)
                                               channels (server :channels)]
                                           (dommy/set-html! channel-select-e
                                                            (html (map (fn [c] [:option c]) channels))))))
    ((.-onselect server-select-e))

    (defn search-button-on-click []
      (let [search-text (dommy/value search-e)
            reg (try (re-pattern search-text)
                     (catch :default e
                       (dommy/set-text! msg-e (str "正则解析错误: " e))
                       (throw e)))]
        (if (= search-text "")
          (dommy/set-text! msg-e "正则不能为空")
          (let [server-host (get-selected-str server-select-e)
                channel (get-selected-str channel-select-e)
                log-num (http/get (str (-> js/window .-location .-origin)
                                       "/api/" server-host "/" (encode-uri channel) "/count"))
                api-link (str (-> js/window .-location .-origin)
                              "/api/" server-host "/" (encode-uri channel) "/")
                log-link (str (-> js/window .-location .-origin)
                              "/log/" server-host "/" (encode-uri channel) "/")
                limit 500]
            (go (let [log-num (js/parseInt ((<! log-num) :body))]

                  (defn search-fn [offset]
                    (if-not (> offset log-num)
                      (let [c (http/get (str api-link limit "/" offset))
                            page (inc (/ offset limit))]
                        (dommy/set-text! msg-e (str "共有 " log-num " 条记录，"
                                                    "正在搜索 " offset "-"
                                                    (if (> (+ offset limit) (inc log-num))
                                                      (inc log-num)
                                                      (+ offset limit))))
                        (go (let [r (<! c)
                                  body (r :body)
                                  log (->> (parse-text-log body)
                                           (map #(assoc % :link (str log-link page))))
                                  result (filter #(re-find reg (% :text)) log)
                                  html (parse-log-to-html result)]
                              (dommy/set-html! search-result-e
                                               (str (dommy/html search-result-e) html))
                              (search-fn (+ offset limit)))))
                      (dommy/set-text! msg-e (str "全部 " log-num " 条记录已搜索完毕"))))

                  (dommy/set-html! search-result-e "")
                  (search-fn 0)))))))

    (dommy/listen! search-button-e :click search-button-on-click)))

(let [path (-> js/window .-location .-pathname)]
  (cond
    (string/starts-with? path "/log") (main-log)
    (string/starts-with? path "/search") (main-search)))
