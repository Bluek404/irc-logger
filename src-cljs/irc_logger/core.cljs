(ns irc-logger.core
  (:require [clojure.string :as string]
            [dommy.core :as dommy :refer-macros [sel sel1]])
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

(defn main-log []
  (let [name-el (sel :.user)
        text-el (sel [:.PRIVMSG :.text])]
    (doseq [name-e name-el]
      (let [name (.-innerText name-e)]
        (dommy/add-class! name-e (get-color-class name))))
    (doseq [text-e text-el]
      (let [text (.-innerText text-e)
            parsed (-> text
                       (parse-text)
                       (string/replace #"https?://[\w-\.~!\*'\(\);:@&=+$,\/?%#\[\]]*"
                                       #(str "<a href=\"" % "\">" %
                                             (when (re-find #"\.(?:png|jpg|svg|gif|bmp|jpeg|webp)$" %)
                                               (str "<img src=\"" % "\"/>"))
                                             "</a>")))]
        (set! (.-innerHTML text-e) parsed)))))

(let [path (-> js/window .-location .-pathname)]
  (cond
    (string/starts-with? path "/log") (main-log)))
