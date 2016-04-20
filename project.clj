(defproject irc-logger "0.1.0"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [org.clojure/data.json "0.2.6"]
                 [org.clojure/core.async "0.2.374"]
                 [org.clojure/java.jdbc "0.4.2"]
                 [org.xerial/sqlite-jdbc "3.8.11.2"]
                 [hiccup "1.0.5"]
                 [compojure "1.5.0"]
                 [http-kit "2.1.18"]
                 [javax.servlet/servlet-api "2.5"]
                 [com.cemerick/url "0.1.1"]

                 ; cljs
                 [org.clojure/clojurescript "1.8.40"]
                 [hiccups "0.3.0"]
                 [prismatic/dommy "1.1.0"]
                 [cljs-http "0.1.40"]]

  :plugins [[lein-cljsbuild "1.1.3"]]
  :hooks [leiningen.cljsbuild]
  :cljsbuild {:builds
              [{:source-paths ["src-cljs"]
                :compiler {:output-to "resources/public/js/script.js"
                           :main irc-logger.core
                           :optimizations :advanced
                           :pretty-print false}}
               {:id "dev"
                :source-paths ["src-cljs"]
                :compiler {
                           :output-to "resources/public/js/script.js"
                           :main irc-logger.core
                           :optimizations :whitespace
                           :pretty-print true}}]}

  :main ^:skip-aot irc-logger.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
