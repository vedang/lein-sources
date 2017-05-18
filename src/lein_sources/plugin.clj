(ns lein-sources.plugin
  "A leiningen plugin to add sources and javadoc jars to the classpath."
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.pprint :refer [pprint]]
            [leiningen.core.main :as lcm]
            [leiningen.core.project :as p])
  (:import org.sonatype.aether.resolution.DependencyResolutionException))

(defn- find-transitive-dependencies
  [dependencies repositories]
  (try (-> (aether/resolve-dependencies :coordinates dependencies
                                        :repositories repositories)
           keys
           set)
       (catch DependencyResolutionException e
         (lcm/info (format "Dependency Resolution Exception when finding transitive deps. \nTrace: %s"
                           dependencies
                           e)))))

(defn- resolve-artifact
  [dependency repositories classifier]
  (try
    (let [dep (concat dependency [:classifier classifier])]
      (->> (find-transitive-dependencies [dep]
                                         repositories)
           (filter (partial = dep))
           first))
    (catch DependencyResolutionException e
      (lcm/info (format "Dependency Resolution Exception for dep: %s, classifier: %s. \nTrace: %s"
                        dependency
                        classifier
                        e)))))

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
                s (conj s)
                j (conj j))))
          []
          deps))

(defn- add-deps-to-project
  [project source-dependencies]
  (if (seq source-dependencies)
    (let [lein-sources-profile {:dependencies source-dependencies}]
      (p/merge-profiles project [lein-sources-profile]))
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
    (let [p (add-deps-to-project project source-dependencies)]
      (lcm/info "Final Project Map: \n"
                (with-out-str (pprint p))))))
