(ns lein-sources.plugin
  "A leiningen plugin to add sources and javadoc jars to the classpath."
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [leiningen.core.main :as lcm])
  (:import java.io.FileNotFoundException
           org.sonatype.aether.resolution.DependencyResolutionException))

(def missing-lein-sources-file
  "/Users/vedang/.lein/lein-sources.missing.edn")

(def known-failed-deps
  (atom
   (reduce (fn [acc l]
             (conj acc (edn/read-string l)))
           #{}
           (try (line-seq (io/reader missing-lein-sources-file))
                (catch FileNotFoundException _
                  [])))))

(defn- find-transitive-dependencies
  [dependencies repositories]
  (if (@known-failed-deps dependencies)
    (lcm/info (format "Known Dependency Resolution Failure: %s"
                      dependencies))
    (try (-> (aether/resolve-dependencies :coordinates dependencies
                                          :repositories repositories)
             keys
             set)
         (catch DependencyResolutionException e
           (spit missing-lein-sources-file
                 (str dependencies "\n")
                 :append true)
           (swap! known-failed-deps conj dependencies)
           (lcm/info (format "Dependency Resolution Exception when finding transitive deps: %s \nTrace: %s"
                             dependencies
                             e))))))

(defn- resolve-artifact
  [dependency repositories classifier]
  (let [dep (concat dependency [:classifier classifier])]
    (->> (find-transitive-dependencies [dep]
                                       repositories)
         (filter (partial = dep))
         first)))

(defn- resolve-source-artifact
  [dependency repositories]
  (resolve-artifact dependency repositories "sources"))

(defn- resolve-javadoc-artifact
  [dependency repositories]
  (resolve-artifact dependency repositories "javadoc"))

(defn- resolve-source-dependencies
  [deps reps opts]
  (reduce (fn [sources-deps d]
            (let [s (when (:resolve-source-artifacts? opts)
                      (resolve-source-artifact d reps))
                  j (when (:resolve-javadoc-artifacts? opts)
                      (resolve-javadoc-artifact d reps))]
              (cond-> sources-deps
                s (conj (into s [:scope "test"]))
                j (conj (into j [:scope "test"])))))
          []
          deps))

(defn- add-deps-to-project
  [project source-dependencies]
  (if (seq source-dependencies)
    (-> project
        (update :dependencies
                (fnil into [])
                source-dependencies)
        (update :dependencies
                reverse))
    (do (lcm/info "No source dependencies to add!")
        project)))

(defn middleware
  [{:keys [repositories dependencies lein-sources-opts] :as project}]
  (lcm/info "Inside lein-sources!")
  (let [reps (into aether/maven-central repositories)
        transitive-dependencies (find-transitive-dependencies dependencies reps)
        source-dependencies (resolve-source-dependencies transitive-dependencies
                                                         reps
                                                         lein-sources-opts)]
    (lcm/info "Adding the following dependencies to :lein-sources profile:\n"
              (with-out-str (pprint source-dependencies)))
    (let [new-project (add-deps-to-project project source-dependencies)]
      (spit "/Users/vedang/.lein/lein-sources-final.edn"
            (with-out-str (pprint new-project)))
      new-project)))
