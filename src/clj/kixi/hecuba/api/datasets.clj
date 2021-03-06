(ns kixi.hecuba.api.datasets
  (:require
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [kixi.hecuba.storage.db :as db]
   [kixi.hecuba.security :as sec]
   [kixi.hecuba.data :as data]
   [kixi.hecuba.api :as api :refer (decode-body authorized?)]
   [liberator.core :refer (defresource)]
   [liberator.representation :refer (ring-response)]
   [kixi.hecuba.storage.uuid :as uuid :refer (uuid-str)]
   [kixi.hecuba.api.devices :as d]
   [kixi.hecuba.web-paths :as p]
   [cheshire.core :as json]
   [kixi.hecuba.data.programmes :as programmes]
   [kixi.hecuba.data.entities :as entities]
   [kixi.hecuba.data.devices :as devices]
   [kixi.hecuba.data.sensors :as sensors]
   [kixi.hecuba.data.datasets :as datasets]
   [kixi.hecuba.data.entities.search :as search]
   [kixi.hecuba.data.users :as users]
   [kixi.hecuba.security :refer (has-admin? has-programme-manager? has-project-manager? has-user?) :as sec]
   [clojure.core.match :refer (match)]))

(def ^:private entity-dataset-resource (p/resource-path-string :entity-dataset-resource))

(defn allowed?* [entity-id programme-id project-id allowed-programmes allowed-projects role request-method store]
  (log/infof "allowed?* programme-id: %s project-id: %s allowed-programmes: %s allowed-projects: %s role: %s request-method: %s"
             programme-id project-id allowed-programmes allowed-projects role request-method)
  (db/with-session [session (:hecuba-session store)]
    (let [all-datasets (datasets/get-all session)
          public?      (-> (search/get-by-id entity-id (:search-session store)) :public_access)]
      (match  [(has-admin? role)
               (has-programme-manager? programme-id allowed-programmes)
               (has-project-manager? project-id allowed-projects)
               (has-user? programme-id allowed-programmes project-id allowed-projects)
               request-method]

              [true _ _ _ _]    [true {::items all-datasets}]
              [_ true _ _ _]    [true {::items all-datasets}]
              [_ _ true _ _]    [true {::items all-datasets}]
              [_ _ _ true :get] [true {::items all-datasets}]
              [_ _ _ _ :get]    [true {::items (when public? all-datasets)}]
              :else false))))

(defn sensors-for-dataset
  "Returns all the sensors for the given dataset."
  [{:keys [operands]} store]
  (db/with-session [session (:hecuba-session store)]
    (let [parsed-sensors (mapv (fn [s] (string/split s #"~")) operands)
          sensor (fn [[device_id sensor_id]]
                   (sensors/get-by-id {:device_id device_id :sensor_id sensor_id} session))]
      (->> (map sensor parsed-sensors)
           (map #(sensors/merge-sensor-metadata store %))))))

(defn synthetic-device [entity_id device_id description]
  (hash-map :description     description
            :entity_id       entity_id
            :device_id       device_id
            :privacy         "private"
            :synthetic       true))

(defn get-period [operation parents]
  (let [parent-period (:period (first parents))]
    (case operation
      :sum parent-period
      :subtract parent-period
      :divide "INSTANT"
      :multiply-series-by-field parent-period
      :divide-series-by-field parent-period
      :total-usage-weekly "PULSE"
      :total-usage-monthly "PULSE"
      :tariff-calculation "PULSE"
      "PULSE")))

(defn synthetic-sensor [sensor_id operation type device_id unit parents]
  {:device_id  device_id
   :type       type
   :sensor_id  sensor_id
   :unit       unit
   :period     (get-period operation parents)
   :resolution "3600" ;; based on hourly_rollups
   :synthetic  true})

(defn synthetic-sensor-metadata [sensor_id device_id & [range]]
  (merge {:sensor_id sensor_id
          :device_id device_id}
         (when-let [{:keys [start-date end-date]} range]
           {:calculated_datasets {"start" start-date "end" end-date}})))

(defn- entity_id-from [ctx]
  (get-in ctx [:request :route-params :entity_id]))

(defn- name-from [ctx]
  (get-in ctx [:request :route-params :name]))

(defn create-output-sensors [device_id sensor_id unit type operation parents]
  (let [synthetic (synthetic-sensor sensor_id operation type device_id unit parents)]
    (:readings (d/create-default-sensors {:readings [synthetic]}))))

(defn insert-output-sensors [store range sensors]
  (db/with-session [session (:hecuba-session store)]
    (doseq [sensor sensors]
      (let [{:keys [device_id sensor_id]} sensor
            metadata (synthetic-sensor-metadata sensor_id device_id range)]
        (datasets/insert-sensor sensor metadata session)))))

(defn index-allowed? [store]
  (fn [ctx]
    (let [{:keys [body request-method session params]} (:request ctx)
          {:keys [projects programmes role]} (sec/current-authentication session)
          entity_id (:entity_id params)
          {:keys [project_id programme_id]} (when entity_id
                                              (search/get-by-id entity_id (:search-session store)))]
      (when (and project_id programme_id)
        (allowed?* entity_id programme_id project_id programmes projects role request-method store)))))

;; TODO Check that sensors periods are a valid combination
(defn index-malformed? [ctx]
  (let [request (:request ctx)
        method  (:request-method request)
        {:keys [route-params request-method]} request
        entity_id (:entity_id route-params)]
    (case method
      :post (let [body (decode-body request)
                  {:keys [operation]} body]
              (if (some #{operation} ["divide" "sum" "subtract" "multiply-series-by-field"
                                      "divide-series-by-field" "total-usage-weekly" "total-usage-monthly"
                                      "tariff-calculation-with-standing-charges"
                                      "tariff-calculation-without-standing-charges"
                                      "min-for-day" "min-for-day-morning" "min-for-day-day" "min-for-day-afteroon"
                                      "min-for-day-night" "avg-for-day" "avg-for-day-morning" "avg-for-day-day"
                                      "avg-for-day-evening" "avg-for-day-night" "max-for-day" "max-for-day-morning"
                                      "max-for-day-day" "max-for-day-evening" "max-for-day-night"
                                      "min-rolling-4-weeks" "min-rolling-4-weeks-morning" "min-rolling-4-weeks-day"
                                      "min-rolling-4-weeks-evening" "min-rolling-4-weeks-night" "avg-rolling-4-weeks"
                                      "avg-rolling-4-weeks-morning" "avg-rolling-4-weeks-average" "avg-rolling-4-weeks-average"
                                      "avg-rolling-4-weeks-average" "max-rolling-4-weeks" "max-rolling-4-weeks-morning"
                                      "max-rolling-4-weeks-day" "max-rolling-4-weeks-evening" "max-rolling-4-weeks-night"])
                [false {:dataset body}]
                true))
      :get (if (seq entity_id) false true)
      false)))

(defn all-sensors-exist? [entity body store]
  (let [{:keys [operation operands name]} body
        sensors  (sensors-for-dataset body store)]
    (when (and (seq entity)
               (seq operands)
               (= (count operands) (count sensors)))
      [true {::items {:entity_id (:entity_id entity) :sensors sensors :operation operation :operands operands :name name}}])))

(defn sensor-and-field-exist? [entity body store]
  (let [{:keys [operation operands name]} body
        sensors  (sensors-for-dataset {:operands (take 1 operands)} store)]
    (when (and (seq entity)
               (pos? (count sensors)))
      [true {::items {:entity_id (:entity_id entity) :sensors sensors :operation operation :operands operands :name name}}])))

(defmulti exists? (fn [entity body store] (:operation body)))

(defmethod exists? :sum [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :divide [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :subtract [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :multiply [entity body store]
  (all-sensors-exist? entity body store))

(defmethod exists? :multiply-series-by-field [entity body store]
  (sensor-and-field-exist? entity body store))

(defmethod exists? :divide-series-by-field [entity body store]
  (sensor-and-field-exist? entity body store))

;; Default for e.g. min-for-day, max-for-day, min-for-day-morning,
;; total-usage-weekly, tariff-calculation-weekly, etc.
(defmethod exists? :default [entity body store]
  (all-sensors-exist? entity body store))

(defn index-exists? [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [{:keys [request dataset]} ctx
          {:keys [route-params request-method]} request
          entity_id (:entity_id route-params)
          entity    (search/get-by-id entity_id (:search-session store))
          editable? (:public_access entity)]
      (case request-method
        :post (let [{:keys [operation]} dataset]
                (exists? entity (assoc dataset :operation (keyword (.toLowerCase operation))) store))
        :get (let [items (datasets/get-all entity_id session)]
               {::items (mapv #(assoc % :editable editable?) items)})))))

(defn stringify [k] (name k))

(defmulti get-unit (fn [item] (:operation item)))
(defmethod get-unit :sum [item]
  (let [{:keys [sensors]} item]
    (:unit (first sensors))))
(defmethod get-unit :subtract [item]
  (let [{:keys [sensors]} item]
    (:unit (first sensors))))
(defmethod get-unit :divide [item]
  (let [{:keys [sensors]} item]
    (str (:unit (first sensors)) "/" (:unit (last sensors)))))
(defmethod get-unit :multiply-series-by-field [item]
  (let [{:keys [sensors operands]} item
        [field unit] (string/split (last operands) #"~")]
    (str (:unit (first sensors)) "/" unit)))
(defmethod get-unit :divide-series-by-field [item]
  (let [{:keys [sensors operands]} item
        [field unit] (string/split (last operands) #"~")]
    (str (:unit (first sensors)) "/" unit)))
(defmethod get-unit :tariff-calculation-with-standing-charges [item]
  "£")
(defmethod get-unit :tariff-calculation-without-standing-charges [item]
  "£")
(defmethod get-unit :default [item]
  (:unit (first (:sensors item))))

(defn index-post! [store ctx]
   (db/with-session [session (:hecuba-session store)]
     (let [{:keys [sensors operation operands entity_id name] :as item} (::items ctx)
           unit          (get-unit item)
           dataset_id    (uuid-str)
           device_id     (uuid-str)
           device        (synthetic-device entity_id device_id "Synthetic")
           operation-str (stringify operation)
           range         (when-let [[start end] (sensors/range-for-all-sensors sensors)]
                           {:start-date start :end-date end})
           sensor_id     (uuid-str)
           output-sensors (create-output-sensors device_id sensor_id unit name operation sensors)]
       ;; Insert synthetic device
       (devices/insert session entity_id device)
       ;; Insert synthetic sensors
       (insert-output-sensors store range output-sensors)
       ;; Insert dataset
       (datasets/insert {:entity_id  entity_id
                         :dataset_id dataset_id
                         :sensor_id  sensor_id
                         :name       name
                         :operands   operands
                         :operation  operation-str
                         :device_id  device_id} session)
       ;; Refresh entity in ES
       (-> (search/searchable-entity-by-id entity_id session)
           (search/->elasticsearch (:search-session store)))
       (hash-map ::dataset_id dataset_id
                 ::entity_id entity_id))))

(defn index-handle-ok [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [items (::items ctx)]
      (api/render-items (:request ctx) items))))

(defn index-handle-created [ctx]
  (let [entity_id   (::entity_id ctx)
        dataset_id  (::dataset_id ctx)]
    (if (and entity_id dataset_id)
      (let [location (format entity-dataset-resource entity_id dataset_id)]
        (ring-response {:headers {"Location" location}
                        :body (json/encode {:location location
                                            :status "OK"
                                            :version "4"})}))
      (ring-response {:status 400 :body (json/encode "Provide valid entity_id, sensors and operation.")}))))

(defresource index [store]
  :allowed-methods       #{:get :post}
  :available-media-types #{"application/edn" "application/json"}
  :known-content-type?   #{"application/edn" "application/json"}
  :authorized?           (authorized? store)
  :allowed?              (index-allowed? store)
  :malformed?            index-malformed?
  :exists?               (partial index-exists? store)
  :post-to-missing?      (constantly false)
  :post!                 (partial index-post! store)
  :handle-ok             (partial index-handle-ok store)
  :handle-created        index-handle-created)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resource

(defn resource-allowed? [store]
  (fn [ctx]
    (let [{:keys [body request-method session params]} (:request ctx)
          {:keys [projects programmes role]} (sec/current-authentication session)
          entity_id (:entity_id params)
          {:keys [project_id programme_id]} (when entity_id
                                              (search/get-by-id entity_id (:search-session store)))]
      (when (and project_id programme_id)
        (allowed?* entity_id programme_id project_id programmes projects role request-method store)))))

;; TODO Add resource-malformed? and check whether all sensors are of the same period
(defn resource-exists? [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [request      (:request ctx)
          body         (decode-body request)
          route-params (:route-params request)
          {:keys [entity_id dataset_id]} route-params]
      (log/infof "resource-exists? %s:%s" entity_id dataset_id)

      (when-let [item (datasets/get-by-id dataset_id session)]
        {:existing-dataset item :new-dataset body}
        #_(throw (ex-info (format "Cannot find item of id %s")))))))

(defn recreate-dataset [user_id old-dataset new-dataset store]
  (db/with-session [session (:hecuba-session store)]
    (let [device_id         (:device_id old-dataset)]
      ;; Deleting old
      (let [sensors-to-delete (sensors/get-sensors device_id session)]
        (when (seq sensors-to-delete)
          (doseq [{:keys [device_id sensor_id]} sensors-to-delete]
            (log/info "Deleting: " device_id sensor_id)
            (sensors/delete {:device_id device_id :sensor_id sensor_id} session))))
      ;; Inserting new
      (let [{:keys [name operation]} new-dataset
            entity            (search/get-by-id (:entity_id old-dataset) (:search-session store))
            [_ item]          (exists? entity new-dataset store)
            sensors           (-> item ::items :sensors)
            range             (when-let [[start end] (sensors/range-for-all-sensors sensors)]
                                {:start-date start :end-date end})
            unit              (get-unit (assoc new-dataset :sensors sensors))
            sensor_id         (:sensor_id old-dataset)
            synthetic-sensors (create-output-sensors device_id sensor_id unit name operation sensors)]
        (doseq [sensor synthetic-sensors]
          (let [{:keys [device_id sensor_id]} sensor
                metadata (synthetic-sensor-metadata sensor_id device_id range)]
            (log/info "Inserting synthetic sensor: " (:device_id sensor) (:type sensor))
            (datasets/insert-sensor (assoc sensor :device_id device_id :user_id user_id) metadata session)))))))

(defn resource-put! [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [old-dataset             (:existing-dataset ctx)
          {:keys [entity_id device_id]} old-dataset
          new-dataset             (:new-dataset ctx)
          dataset-to-update       (merge {:name      (:name new-dataset)
                                          :operands  (:operands new-dataset)}
                                         (when-let [operation (:operation new-dataset)]
                                           {:operation (keyword (.toLowerCase (:operation new-dataset)))}))
          username                (sec/session-username (-> ctx :request :session))
          user_id                 (:id (users/get-by-username session username))]
      ;; Recreate synthetic sensors for this dataset
      (recreate-dataset user_id old-dataset dataset-to-update store)
      ;; Update dataset - using insert because we want to overwrite the list of operands instead of deleting
      ;; operands that changed and adding new ones.
      (datasets/insert (assoc new-dataset :device_id device_id :entity_id entity_id
                              :dataset_id (:dataset_id old-dataset)) session)
      ;; Update entity in ES to include updated sensors
      (let [search-response (-> (search/searchable-entity-by-id entity_id session)
                                (search/->elasticsearch (:search-session store)))]))))

(defn resource-delete! [store ctx]
  (db/with-session [session (:hecuba-session store)]
    (let [existing-dataset (:existing-dataset ctx)
          {:keys [device_id entity_id dataset_id]} existing-dataset
          response        (datasets/delete dataset_id entity_id device_id false session)
          search-response (-> (search/searchable-entity-by-id entity_id session)
                              (search/->elasticsearch (:search-session store)))]
      "Delete Accepted")))

(defn resource-handle-ok [ctx]
  (let [item (::item ctx)]
    (api/render-item ctx item)))

(defresource resource [store]
  :allowed-methods       #{:get :put :delete}
  :available-media-types #{"application/edn" "application/json"}
  :known-content-type?   #{"application/edn" "application/json"}
  :authorized?           (authorized? store)
  :allowed?              (resource-allowed? store)
  :exists?               (partial resource-exists? store)
  :put!                  (partial resource-put! store)
  :delete!               (partial resource-delete! store)
  :handle-ok             resource-handle-ok)
