(ns kixi.hecuba.data.measurements
  (:require [clj-time.core          :as t]
            [clj-time.coerce        :as tc]
            [kixi.hecuba.time       :as time]
            [kixi.hecuba.storage.db :as db]
            [kixi.hecuba.data.misc  :as misc]
            [qbits.hayt             :as hayt]
            [kixi.hecuba.webutil    :as util]
            [clojure.tools.logging  :as log]))

(defn sensor_metadata-for [store sensor_id]
  (let [{:keys [type device_id]} sensor_id]
    (db/with-session [session (:hecuba-session store)]
      (first (db/execute session
                         (hayt/select
                          :sensor_metadata
                          (hayt/where [[= :device_id device_id]
                                       [= :type type]])))))))

(defn resolve-start-end [store type device_id start end]
  (mapv tc/to-date-time
        (if (not (and start end))
          (let [sm (sensor_metadata-for store {:type type :device_id device_id})
                [lower upper] ((juxt :lower_ts :upper_ts) sm)]
            [(or start lower)
             (or end upper)])
          [start end])))

(defn delete
  "WARNING: This will delete data in 1 month chunks based on the dates
  passed in."
  [session device_id type start-date end-date]
  (log/infof "Deleting Measurements for %s:%s from %s to %s" device_id type start-date end-date)
  (let [months (time/range->months start-date end-date)
        years  (time/range->years start-date end-date)]
    (concat
     (db/execute session
                 (hayt/delete :partitioned_measurements
                              (hayt/where [[= :device_id device_id]
                                           [= :type type]
                                           [:in :month months]])))
     (db/execute session
                 (hayt/delete :hourly_rollups
                              (hayt/where [[= :device_id device_id]
                                           [= :type type]
                                           ;; FIXME: This should be partitioned by years, but isn't
                                           ;; [:in :year years]
                                           ])))
     (db/execute session
                 (hayt/delete :daily_rollups
                              (hayt/where [[= :device_id device_id]
                                           [= :type type]]))))))

(defn all-measurements
  "Returns a sequence of all the measurements for a sensor
   matching (type,device_id). The sequence pages to the database in the
   background. The page size is a clj-time Period representing a range
   in the timestamp column. page size defaults to (clj-time/hours 1)"
  ([store sensor_id & [opts]]
     (let [{:keys [type device_id]} sensor_id
           {:keys [page start end] :or {page (t/hours 1)}} opts
           [start end] (resolve-start-end store type device_id start end)]
       (when (and start end)
         (let  [next-start (t/plus start page)]
           (db/with-session [session (:hecuba-session store)]
             (lazy-cat (db/execute session
                                   (hayt/select :partitioned_measurements
                                                (hayt/where [[= :device_id device_id]
                                                             [= :type type]
                                                             [= :month (misc/get-month-partition-key start)]
                                                             [>= :timestamp start]
                                                             [< :timestamp next-start]]))
                                   nil)
                       (when (t/before? next-start end)
                         (all-measurements store sensor_id (merge opts {:start next-start :end end}))))))))))


(defn measurements-for-range
  "Returns a lazy sequence of measurements for a sensor matching type and device_id for a specified
  datetime range."
  [store sensor {:keys [start-date end-date]} page]
  (let [device_id  (:device_id sensor)
        type       (:type sensor)
        next-start (t/plus start-date page)]
    (db/with-session [session (:hecuba-session store)]
      (lazy-cat (db/execute session
                               (hayt/select :partitioned_measurements
                                            (hayt/where [[= :device_id device_id]
                                                         [= :type type]
                                                         [= :month (misc/get-month-partition-key start-date)]
                                                         [>= :timestamp start-date]
                                                         [< :timestamp next-start]]))
                               nil)
                (when (t/before? next-start end-date)
                  (measurements-for-range store sensor {:start-date next-start :end-date end-date} page))))))

(defn- retrieve-measurements-for-months [session [month & more]  where]
  (log/info "Got month " month)
  (when month
    (lazy-cat (db/execute session
                          (hayt/select :partitioned_measurements
                                       (hayt/where (conj where [= :month month]))))
              (when (seq more)
                (retrieve-measurements-for-months session more where)))))

(defn retrieve-measurements
  "Iterate over a sequence of months and concatanate measurements retrieved from the database."
  [session start-date end-date device-id reading-type]
  (let [range  (util/time-range start-date end-date (t/months 1))
        months (map #(util/get-month-partition-key (tc/to-date %)) range)
        where  [[= :device_id device-id]
                [= :type reading-type]
                [>= :timestamp (tc/to-date start-date)]
                [<= :timestamp (tc/to-date end-date)]]]
    (retrieve-measurements-for-months session months where)))
