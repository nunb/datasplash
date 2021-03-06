(ns datasplash.examples
  (:require [clojure.string :as str]
            [datasplash
             [api :as ds]
             [bq :as bq]]
            [clojure.edn :as edn])
  (:gen-class))

;;;;;;;;;;;;;;;
;; WordCount ;;
;;;;;;;;;;;;;;;

;; Port of https://github.com/GoogleCloudPlatform/DataflowJavaSDK/blob/master/examples/src/main/java/com/google/cloud/dataflow/examples/WordCount.java

(defn tokenize
  [l]
  (remove empty? (.split (str/trim l) "[^a-zA-Z']+")))

(ds/defoptions WordCountOptions
  {:input {:type String
           :default "gs://dataflow-samples/shakespeare/kinglear.txt"
           :description "Path of the file to read from"}
   :output {:type String
            :default "kinglear-freqs.txt"
            :description "Path of the file to write to"}
   :numShards {:type Long
               :description "Number of output shards (0 if the system should choose automatically)"
               :default 0}})

(defn run-word-count
  [str-args]
  (let [p (ds/make-pipeline 'WordCountOptions str-args)
        {:keys [input output numShards]} (ds/get-pipeline-configuration p)]
    (->> p
         (ds/read-text-file input {:name "King-Lear"})
         (ds/mapcat tokenize {:name :tokenize})
         (ds/frequencies)
         (ds/map (fn [[k v]] (format "%s: %d" k v)) {:name :format-count})
         (ds/write-text-file output {:num-shards numShards}))))

;;;;;;;;;;;
;; DeDup ;;
;;;;;;;;;;;


;; Port of https://github.com/GoogleCloudPlatform/DataflowJavaSDK/blob/master/examples/src/main/java/com/google/cloud/dataflow/examples/cookbook/DeDupExample.java

(ds/defoptions DeDupOptions
  {:input {:type String
           :default "gs://dataflow-samples/shakespeare/*"
           :description "Path of the file to read from"}
   :output {:type String
            :default "shakespeare-dedup.txt"
            :description "Path of the file to write to"}})

(defn run-dedup
  [str-args]
  (let [p (ds/make-pipeline 'DeDupOptions str-args)
        {:keys [input output]} (ds/get-pipeline-configuration p)]
    (->> p
         (ds/read-text-file input {:name "ReadLines"})
         (ds/distinct {:name "dedup"})
         (ds/write-text-file output {:name "DedupedShakespeare"}))))

;;;;;;;;;;;;
;; Filter ;;
;;;;;;;;;;;;

;; Port of https://github.com/GoogleCloudPlatform/DataflowJavaSDK/blob/master/examples/src/main/java/com/google/cloud/dataflow/examples/cookbook/FilterExamples.java

(ds/defoptions FilterOptions
  {:input {:type String
           :default "clouddataflow-readonly:samples.weather_stations"
           :description "Table to read from, specified as <project_id>:<dataset_id>.<table_id>"}
   :output {:type String
            :default "filterRes.edn"
            :description "Table to write to, specified as <project_id>:<dataset_id>.<table_id>. The dataset_id must already exist. If given a path, writes to edn."}
   :monthFilter {:type Long
                 :default 7
                 :description "Numeric value of month to filter on"}})

(defn run-filter
  [str-args]
  (let [p (ds/make-pipeline 'FilterOptions str-args)
        {:keys [input output monthFilter]} (ds/get-pipeline-configuration p)
        all-rows (->> p
                      (bq/read-bq {:table input})
                      (ds/map (fn [row]
                                (->>
                                 (select-keys row [:year :month :day :mean_temp])
                                 (map (fn [[k v]] (if (string? v) [k (edn/read-string v)] [k v])))
                                 (into {})))
                              {:name "Projection"}))
        global-mean-temp (->> all-rows
                              (ds/combine (ds/mean-fn :mapper :mean_temp))
                              (ds/view))
        filtered-results (->> all-rows
                              (ds/filter (fn [{:keys [month]}] (= month monthFilter)))
                              (ds/filter (fn [{:keys [mean_temp]}]
                                           (let [gtemp (:global-mean (ds/side-inputs))]
                                             (< mean_temp gtemp)))
                                         {:name "ParseAndFilter" :side-inputs {:global-mean global-mean-temp}}))]
    (if (re-find #":[^/]" output)
      (bq/write-bq-table output {:schema [{:name "year" :type "INTEGER"}
                                          {:name "month":type "INTEGER"}
                                          {:name "day"  :type "INTEGER"}
                                          {:name "mean_temp" :type "FLOAT"}]
                                 :create-disposition :if-needed
                                 :write-disposition :truncate}
                         filtered-results)
      (ds/write-edn-file output filtered-results))))

;;;;;;;;;;;;;;;;;;;
;; CombinePerKey ;;
;;;;;;;;;;;;;;;;;;;

;; Port of https://github.com/GoogleCloudPlatform/DataflowJavaSDK/blob/master/examples/src/main/java/com/google/cloud/dataflow/examples/cookbook/CombinePerKeyExamples.java

(ds/defoptions CombinePerKeyOptions
  {:input {:type String
           :default "publicdata:samples.shakespeare"
           :description "Table to read from, specified as <project_id>:<dataset_id>.<table_id>"}
   :output {:type String
            :default "combinePerKeyRes.edn"
            :description "Table to write to, specified as <project_id>:<dataset_id>.<table_id>. The dataset_id must already exist. If given a path, writes to edn."}
   :minWordLength {:type Long
                   :default 8
                   :description "Minimum word length."}})

(defn run-combine-per-key
  [str-args]
  (let [p (ds/make-pipeline 'CombinePerKeyOptions str-args)
        {:keys [input output minWordLength]} (ds/get-pipeline-configuration p)
        results (->> p
                     (bq/read-bq {:table input})
                     (ds/filter (fn [{:keys [word]}] (> (count word) minWordLength)))
                     (ds/map-kv (fn [{:keys [word corpus]}] [word corpus]))
                     (ds/combine
                      (ds/sfn (fn [words] (str/join "," words)))
                      {:scope :per-key})
                     (ds/map (fn [[word plays]] {:word word :all_plays plays})))]
    (if (re-find #":[^/]" output)
      (bq/write-bq-table output {:schema [{:name "word" :type "STRING"}
                                          {:name "all_plays" :type "STRING"}]
                                 :create-disposition :if-needed
                                 :write-disposition :truncate}
                         results)
      (ds/write-edn-file output results))))

;;;;;;;;;;;;;;;
;; MaxPerKey ;;
;;;;;;;;;;;;;;;

;; Port of https://github.com/GoogleCloudPlatform/DataflowJavaSDK/blob/master/examples/src/main/java/com/google/cloud/dataflow/examples/cookbook/MaxPerKeyExamples.java

(ds/defoptions MaxPerKeyOptions
  {:input {:type String
           :default "clouddataflow-readonly:samples.weather_stations"
           :description "Table to read from, specified as <project_id>:<dataset_id>.<table_id>"}
   :output {:type String
            :default "maxperKeyRes.edn"
            :description "Table to write to, specified as <project_id>:<dataset_id>.<table_id>. The dataset_id must already exist. If given a path, writes to edn."}})

(defn run-max-per-key
  [str-args]
  (let [p (ds/make-pipeline 'MaxPerKeyOptions str-args)
        {:keys [input output]} (ds/get-pipeline-configuration p)
        results (->> p
                     (bq/read-bq {:table input})
                     (ds/map-kv (fn [{:keys [month mean_temp]}]
                                  [(edn/read-string month) (double mean_temp)]))
                     (ds/combine (ds/max-fn) {:scope :per-key})
                     (ds/map (fn [[k v]]
                               {:month k :max_mean_temp v})))]
    (if (re-find #":[^/]" output)
      (bq/write-bq-table output {:schema [{:name "month" :type "INTEGER"}
                                          {:name "max_mean_temp" :type "FLOAT"}]
                                 :create-disposition :if-needed
                                 :write-disposition :truncate}
                         results)
      (ds/write-edn-file output results))))

;;;;;;;;;;
;; Main ;;
;;;;;;;;;;

(defn -main
  [job & args]
  (compile 'datasplash.examples)
  (-> (case job
        "word-count" (run-word-count args)
        "dedup" (run-dedup args)
        "filter" (run-filter args)
        "combine-per-key" (run-combine-per-key args)
        "max-per-key" (run-max-per-key args))
      (ds/run-pipeline)))
