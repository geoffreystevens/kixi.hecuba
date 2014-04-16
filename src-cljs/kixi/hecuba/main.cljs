(ns kixi.hecuba.main
  (:require-macros [cljs.core.async.macros :refer [go-loop]])
  (:require
   [om.core :as om :include-macros true]
   [om.dom :as dom :include-macros true]
   [cljs.core.async :refer [<! >! chan put! sliding-buffer close! pipe map< filter< mult tap map>]]
   [ajax.core :refer (GET POST)]
   [clojure.string :as str]
   [cljs-time.core :as t]
   [cljs-time.format :as tf]
   [kixi.hecuba.navigation :as nav]
   [kixi.hecuba.chart :as chart]
   [kixi.hecuba.common :refer (index-of map-replace find-first interval)]
   [kixi.hecuba.history :as history]
   [kixi.hecuba.model :refer (app-model)]
   [kixi.hecuba.sensor :as sensor]))

(enable-console-print!)

(defn blank-tab [data owner]
  (om/component
      (dom/p nil "This page is unintentionally left blank")))

(defn charts-tab [data owner]
  (om/component
      (dom/div nil
           (dom/h1 nil (:title data))
           (om/build chart/chart-figure (:chart data)))))

(defn about-tab [data owner]
  (om/component
      (dom/div nil
           (dom/h1 nil (:title data))
           (dom/p nil "I'm the About tab"))))

(defn documentation-tab [data owner]
  (om/component
      (dom/div nil
           (dom/h1 nil (:title data))
           (dom/p nil "Some documentation"))))

(defn users-tab [data owner]
  (om/component
      (dom/div nil
           (dom/h1 nil (:title data))
           (dom/p nil "List of users"))))

(defn update-when [x pred f & args]
  (if pred (apply f x args) x))

(defn row-for [{:keys [selected data]}]
  (find-first #(= (:id %) selected) data))

(defn uri-for-selection-change
  "Returns the uri to load because of change of selection. Returns nil
   if no change to selection"
  [current-selected selection-key template {{ids :ids} :args}]
  (let [new-selected (get ids selection-key)]
    (when (or (nil? current-selected)
              (nil? new-selected)
              (not= current-selected
                    new-selected))
      (vector new-selected
              (map-replace template ids)))))

(defn ajax [in data path {:keys [template selection-key content-type]} & [chart]]
  (go-loop []
    (when-let [[new-selected uri] (uri-for-selection-change (:selected @data)
                                                            selection-key
                                                            template
                                                            (<! in))]
      (when uri
        (GET uri
             (-> {:handler  (fn [x]
                              (when (= selection-key :sensor)
                                (let [[type _] (str/split new-selected #"-")
                                      unit     (:unit (first (filter #(= (:type %) type) (:readings x))))]
                                  (om/update! chart :unit unit)))
                              (om/update! data (conj path :data) x)
                              (om/update! data (conj path :selected) new-selected))
                  :headers {"Accept" content-type}
                  :response-format :text}
                 (cond-> (= content-type "application/json")
                         (merge {:response-format :json :keywords? true}))))))
    (recur)))

(defn selected-range-change
  [selected selection-key template {{ids :ids search :search} :args}]
  (let [new-selected (get ids selection-key)]
    (when (or (nil? selected)
              (not= selected new-selected))
      (vector new-selected ids search))))

(defn chart-feedback-box [cursor owner]
  (om/component
   (dom/div nil cursor)))

(defn chart-ajax [in data {:keys [template selection-key content-type]}]
  (go-loop []
    (when-let [[new-range ids search] (selected-range-change (:range @data)
                                                      selection-key
                                                      template
                                                      (<! in))]
      (let [[start-date end-date] search
            entity-id                  (get ids :property)
            sensor-id                  (get ids :sensor)
            [type device-id]      (str/split sensor-id #"-")]
        ;; TODO ajax call should not be made on each change, only on this particular cursor update.
        (when (and (not (empty? start-date))
                   (not (empty? end-date))
                   (not (nil? device-id))
                   (not (nil? entity-id))
                   (not (nil? type)))
          (om/update! data :range {:start-date start-date :end-date end-date})
          (om/update! data :sensor sensor-id)
          (let [url (case (interval start-date end-date)
                      :raw (str "/4/entities/" entity-id "/devices/" device-id "/measurements/"
                                type "?startDate=" start-date "&endDate=" end-date)
                      :hourly-rollups (str "/4/entities/" entity-id "/devices/" device-id "/hourly_rollups/"
                                           type "?startDate=" start-date "&endDate=" end-date)
                      :daily-rollups (str "/4/entities/" entity-id "/devices/" device-id "/daily_rollups/"
                                          type "?startDate=" start-date "&endDate=" end-date))]
            (GET url {:handler #(om/update! data :measurements %)
                      :headers {"Accept" "application/json"}
                      :response-format :json
                      :keywords? true})))))
    (recur)))

;; TODO histkey is really id key. resolve name confusion.
(defn table [cursor owner {:keys [histkey path]}]
  (reify
    om/IRender
    (render [_]
      ;; Select the first row
      ;;(put! out {:type :row-selected :row (first (om/get-state owner :data))})
      (let [cols (get-in cursor [:header :cols])
            table-id (str (name histkey) "-table")
            history (om/get-shared owner :history)]
        (dom/div #js {:id (str table-id "-container")}
         (dom/table
          #js {:id table-id
               :className "table table-hover hecuba-table"} ;; table-hover table-stripedso,
          (dom/thead
           nil
           (dom/tr
            nil
            (into-array
             (for [[_ {:keys [label]}] cols]
               (dom/th nil label)))))
          (dom/tbody
           nil
           (into-array
            (for [{:keys [id href ] :as row} (-> cursor :data
                                                (cond-> path path))]
              (dom/tr
               #js {:onClick (fn [_ _ ]
                               (om/update! cursor :selected id)
                               (history/update-token-ids! history histkey id))
                    :className (if (= id (:selected cursor)) "success")
                    ;; TODO use this to scroll the row into view.
                    ;; Possible solution here: http://stackoverflow.com/questions/1805808/how-do-i-scroll-a-row-of-a-table-into-view-element-scrollintoview-using-jquery
                    :id (str table-id "-selected")}
               (into-array
                (for [[k {:keys [href]}] cols]
                  (let [k (if (vector? k) k (vector k))]
                    (dom/td nil (if href
                                  (dom/a #js {:href (get row href)} (get-in row k))
                                  (get-in row k))))))))))))))))

;; TODO datepicker's min and max functions don't work as expected.
;; Might be an issue with using jQuery for reading selected dates.
;; For now added the check below. But need to disable invalid dates
;; in the datepicker instead.
(defn evaluate-dates
  [start-date end-date]
  (let [formatter (tf/formatter "dd-MM-yyyy HH:mm")
        start     (tf/parse formatter start-date)
        end       (tf/parse formatter end-date)]
    (cond
     (t/after? start end) :invalid
     (= start-date end-date) :invalid
     (not= start-date end-date) :valid)))

(defn date-picker
  [cursor owner {:keys [histkey]}]
  (reify
    om/IRender
    (render [_]
      (let [history (om/get-shared owner :history)]
        (dom/div nil
                 (dom/div #js {:className "container"}
                          (dom/div #js {:className "col-sm-3"}
                                   (dom/div #js {:className "form-group"}
                                            (dom/div #js {:className "input-group date" :id "dateFrom" }
                                                     (dom/input #js
                                                                {:type "text"
                                                                 :ref "dateFrom"
                                                                 :data-format "DD-MM-YYYY HH:mm"
                                                                 :className "form-control"
                                                                 :placeholder "Start date"
                                                                 :value (if (empty? (get-in cursor [:chart :range]))
                                                                          ""
                                                                          (get-in cursor [:chart :range :start-date]))})
                                                     (dom/span #js {:className "input-group-addon"}
                                                               (dom/span #js {:className "glyphicon glyphicon-calendar"})))))
                          (dom/div #js {:className "col-sm-3"}
                                   (dom/div #js {:className "form-group"}
                                            (dom/div #js {:className "input-group date" :id "dateTo"}
                                                     (dom/input #js
                                                                {:type "text"
                                                                 :data-format "DD-MM-YYYY HH:mm"
                                                                 :ref "dateTo"
                                                                 :className "form-control"
                                                                 :placeholder "End date"
                                                                 :value (if (empty? (get-in cursor [:chart :range]))
                                                                          ""
                                                                          (get-in cursor [:chart :range :end-date]))})
                                                     (dom/span #js {:className "input-group-addon"}
                                                               (dom/span #js {:className "glyphicon glyphicon-calendar"})))))
                          (dom/button #js {:type "button"
                                           :id "select-dates-btn"
                                           :className  "btn btn-primary btn-large"
                                           :onClick
                                           (fn [e]
                                             (let [start     (-> (om/get-node owner "dateFrom")
                                                                 .-value)
                                                   end       (-> (om/get-node owner "dateTo")
                                                                 .-value)
                                                   formatter (tf/formatter "dd-MM-yyyy HH:mm")]
                                               (if (= :valid (evaluate-dates start end))
                                                 (do
                                                   (history/set-token-search! history [start end])
                                                   (om/update! cursor [:chart :range] {:start-date start :end-date end})
                                                   (om/update! cursor [:chart :message] ""))
                                                 (do
                                                   (om/update! cursor [:chart :range] {:start-date start :end-date end})
                                                   (om/update! cursor [:chart :message] "End date must be later than start date."))
                                                 )))}
                                      "Select dates")))))))

(defmulti render-content-directive (fn [itemtype _ _] itemtype))

(defmethod render-content-directive :text
  [_ item _]
  (dom/p nil item))

(defmethod render-content-directive :table
  [_ item owner]
  (om/build table item {:opts (om/get-state owner :event-chan)}))

(defn make-channel-pair []
  {:in (chan (sliding-buffer 1))
   :out (chan (sliding-buffer 1))})

(defn device-detail [{:keys [selected data] :as cursor} owner]
  (om/component
   (let [row      (first (filter #(= (:id %) selected) data))]
     (let [{:keys [description name
                   latitude longitude]} (:location row)]
       (dom/div nil
                (dom/h3 nil (apply str  "Device Detail "  (interpose \/ (remove nil? [description name])))) ;; TODO add a '-'
                (dom/p nil (str "Latitude: " latitude))
                (dom/p nil (str "Longitude: " longitude)))))))

(defn title-for [cursor & {:keys [title-key] :or {title-key :name}}]
  (some->> (get-in (row-for cursor) (if (vector? title-key) title-key (vector title-key)))
           (str " - ")))

(defn programmes-tab [data owner]
  (let [{tables :tables} data]
    (reify
      om/IWillMount
      (will-mount [_]
        (let [m           (mult (history/set-chan! (om/get-shared owner :history) (chan)))
              tap-history #(tap m (chan))]

          ;; attach a go-loop that fires ajax requests on history changes to each table

          ;;TODO still some cruft to tidy here:  /3 and singular/plural bunk.
          (ajax (tap-history) tables [:programmes] {:template      "/4/programmes/"
                                                    :content-type  "application/edn"
                                                    :selection-key :programme})
          (ajax (tap-history) tables [:projects] {:template      "/4/programmes/:programme/projects"
                                                  :content-type  "application/edn"
                                                  :selection-key :project})
          (ajax (tap-history) tables [:properties] {:template      "/4/projects/:project/properties"
                                                    :content-type "application/json"
                                                    :selection-key :property})
          (ajax (tap-history) tables [:devices] {:template      "/4/entities/:property/devices"
                                                 :content-type  "application/json"
                                                 :selection-key :device})
          (ajax (tap-history) tables [:sensors] {:template "/4/entities/:property/devices/:device"
                                                 :content-type "application/json"
                                                 :selection-key :sensor} (:chart data))
          (ajax (tap-history) tables [:sensor-select] {:template     "/4/entities/:property/sensors"
                                                       :content-type "application/json"
                                                       :selection-key :property})
          (chart-ajax (tap-history) (:chart data) {:template "/4/entities/:property/devices/:device/measurements?startDate=:start-date&endDate=:end-date"
                                                   :content-type  "application/json"
                                                   :selection-key :range})
          ))
      om/IRender
      (render [_]
        ;; Note dynamic titles for each of the sections.

        ;; TODO sort out duplication here, wrap (on/build table ...) calls probably.
        ;;      we need to decide on singular/plural for entities. I vote singular.
        ;;
        (let [{:keys [programmes projects properties devices sensor-select]} tables]
          (dom/div nil
                 (dom/h1 {:id "programmes"} (:title data))
                 (om/build table programmes {:opts {:histkey :programme}})
                 (dom/h2 {:id "projects"} (str  "Projects " (title-for programmes)))
                 (om/build table projects {:opts {:histkey :project}})
                 (dom/h2 {:id "properties"} (str "Properties" (title-for projects)))
                 (om/build table properties {:opts {:histkey :property}})
                 (dom/h2 {:id "devices"} "Devices" (title-for properties :title-key :addressStreetTwo))
                 (om/build table devices {:opts {:histkey :device}})
                 (om/build device-detail devices)
                 (dom/h2 {:id "sensors"} "Sensors" (title-for devices :title-key [:location :name]))
                 (om/build sensor/table data {:opts {:histkey :sensor
                                                     :path    :readings}})
                 (om/build sensor/define-data-set-button data)
                 (dom/h2 nil "Chart")
                 (dom/div #js {:id "date-picker"})
                 (dom/p nil "Note: When you select something to plot on a given axis, you will only be able to plot other items of the same unit on that axis.")
                 (om/build date-picker data {:opts {:histkey :range}})
                 (om/build chart-feedback-box (get-in data [:chart :message]))
                 (dom/div #js {:id "chart"})
                 (om/build chart/chart-figure (:chart data))
                 (om/build sensor/selection-dialog (:tables data)
                           {:opts {:id "sensor-selection-dialog"
                                   :handler (fn [e]
                                              (.preventDefault e)
                                              (POST (str "/4/entities/" (:selected @properties) "/datasets")
                                                    {:params (:sensor-group @sensor-select)
                                                     :handler #(println "Yah!")
                                                     :error-handler #(println "Error!")
                                                     :response-format "application/edn"
                                                     :keywords? true}))}})))))))

(defn tab-container [tabs]
  (fn [data owner]
    (om/component
        (dom/div nil
             (let [selected (-> data :tab-container :selected)]
               (if-let [tab (get tabs selected)]
                 (om/build tab (->> data :tab-container :tabs (filter #(= (:name %) selected)) first))
                 (om/build blank-tab data)))))))

(defn ^:export handle-left-nav [menu-item]
  ;; Currently we implement a one-to-one correspondence between the
  ;; left-hand-menu and the tab container, but in due course 'Project'
  ;; and 'Properties' will cause a scroll to a location under the
  ;; 'Programmes' tab
  (swap! app-model assoc-in [:tab-container :selected] menu-item))

(defn FOO []
  (let [path [:tab-container :tabs 3 :tables :sensor-select :sensor-group]]
    (println "AM:" (type(-> @app-model (get-in path))))
    (println "AM:" (pr-str (-> @app-model (get-in path))))))

(om/root
    (let [{:keys [in out] :as pair} (make-channel-pair)]
      (go-loop []
               (when-let [n (<! out)]
                 (handle-left-nav n)
                 (recur)))
      (nav/nav pair))
    app-model
    {:target (.getElementById js/document "hecuba-nav")})

(om/root (tab-container {:about about-tab
                         :programmes programmes-tab
                         :charts charts-tab
                         :documentation documentation-tab
                         :users users-tab})
         app-model
         {:target (.getElementById js/document "hecuba-tabs")
          :shared {:history (history/new-history [:programme :project :property :device :sensor :measurement])}})
