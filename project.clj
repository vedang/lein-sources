(defproject lein-sources "0.1.0"
  :description "A leiningen plugin to add sources and javadocs jars to the classpath."
  :url "https://vedang.me/techlog"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :eval-in-leiningen true
  :dependencies [[com.cemerick/pomegranate "1.0.0"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.9.0"]
                                  [leiningen-core "2.8.1"]]}})
