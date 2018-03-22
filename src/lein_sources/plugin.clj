(ns lein-sources.plugin
  "A leiningen plugin to add sources and javadoc jars to the classpath."
  (:require [cemerick.pomegranate.aether :as aether]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [leiningen.core.main :as lcm]
            [leiningen.core.user :as lcu])
  (:import java.io.FileNotFoundException
           org.eclipse.aether.resolution.DependencyResolutionException))

(def missing-lein-sources-file
  "A file to track jars which we know don't have accompanying sources
  or javadoc jars."
  (str (lcu/leiningen-home) "/lein-sources-missing.edn"))
(def final-lein-sources-project-file
  "A file to store the final project map after lein-sources has
  manipulated the dependencies vector. This is useful when debugging."
  (str (lcu/leiningen-home) "/lein-sources-final.edn"))

(def known-failed-deps
  "Track jars which we know don't have source or javadoc jars in-mem.
  We read this information from a file - `missing-lein-sources-file` -
  which persists across multiple invocations of lein-sources. The file
  is updated by `find-transitive-dependencies`."
  (atom
   (reduce (fn [acc l]
             (conj acc (edn/read-string l)))
           #{}
           (try (line-seq (io/reader missing-lein-sources-file))
                (catch FileNotFoundException _
                  [])))))

(defn- find-transitive-dependencies
  "Given the dependencies of the project, use aether to get a list of
  all the transitive deps.

  I use this function to try and fetch source and javadoc jars. It
  tracks DependencyResolutionExceptions in `missing-lein-sources-file`
  so as to avoid fetching jars that we know don't exist."
  [dependencies repositories &
   {:keys [track-failed-deps?]
    :or {track-failed-deps? true}}]
  (if (@known-failed-deps dependencies)
    (lcm/info (format "lein-sources: Known Dependency Resolution Failure: %s"
                      dependencies))
    (try (-> (aether/resolve-dependencies
              :coordinates dependencies
              :repositories (map (fn [[id settings]]
                                   (let [settings-map (if (string? settings)
                                                        {:url settings}
                                                        settings)]
                                     [id (lcu/resolve-credentials settings-map)]))
                                 repositories))
             keys
             set)
         (catch DependencyResolutionException e
           (when track-failed-deps?
             (spit missing-lein-sources-file
                   (str dependencies "\n")
                   :append true)
             (swap! known-failed-deps conj dependencies))
           (lcm/info (format "lein-sources: Dependency Resolution Exception when finding transitive deps: %s \nTrace: %s"
                             dependencies
                             e))))))

(defn- resolve-artifact
  "Given a dependency and a classifier for the dep - either
  \"sources\" or \"javadoc\" - try and resolve the artifact. Returns
  the dependency on success, and nil on failure."
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
  "Given the calculated `source-dependencies` and the original
  `project`, update the dependencies in the project."
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
  "Given the dependencies of the project, add source jars wherever
  such jars are available."
  [{:keys [repositories dependencies lein-sources-opts] :as project}]
  (lcm/info "lein-sources: Begin Magic!")
  (try (let [reps (into aether/maven-central repositories)
             ;; When finding the initial transitive-deps, there is no need
             ;; for me to track any DependencyResolutionException.
             _ (lcm/debug "lein-sources: Found reps!")
             transitive-dependencies (find-transitive-dependencies dependencies
                                                                   reps
                                                                   :track-failed-deps? false)
             _ (lcm/debug "lein-sources: Found transitive-deps!")
             source-dependencies (resolve-source-dependencies transitive-dependencies
                                                              reps
                                                              lein-sources-opts)
             _ (lcm/debug "lein-sources: Found source-deps!")]
         (lcm/info "lein-sources: Adding the following dependencies to profile:\n"
                   (with-out-str (pprint source-dependencies)))
         (let [new-project (add-deps-to-project project source-dependencies)]
           (when (:debug? lein-sources-opts)
             (spit final-lein-sources-project-file
                   (with-out-str (pprint new-project))))
           new-project))
       (catch Exception e
         (lcm/info "lein-sources: Caught Exception! :\n"
                   (with-out-str (pprint e)))
         project)))
