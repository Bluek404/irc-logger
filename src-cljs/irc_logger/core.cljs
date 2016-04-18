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

(defn main-log []
  (prn "OK")
  (let [name-el (sel :.user)]
    (doseq [name-e name-el]
      (let [name (.-innerText name-e)]
        (dommy/add-class! name-e (get-color-class name))))))

(let [path (-> js/window .-location .-pathname)]
  (cond
    (string/starts-with? path "/log") (main-log)))
