(ns kixi.hecuba.web.resources
  (:require
   [clojure.tools.logging :refer (infof)]
   [liberator.core :refer (defresource)]
   [liberator.representation :refer (ring-response)]
   [kixi.hecuba.protocols :refer (upsert! item items) :as protocols]
   [bidi.bidi :refer (->Redirect path-for)]
   [hiccup.core :refer (html)]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as string]
   [clojure.pprint :refer (pprint)]
   [camel-snake-kebab :as csk])
  (:import javax.xml.bind.DatatypeConverter))

(def base-media-types ["text/html" "application/json" "application/edn"])

(defn render-items-html
  "Render some HTML for the text/html representation. Mostly this is for
  debug, to be able to check the data without too much UI logic
  involved."
  [items]
  (let [fields (remove #{:hecuba/name :hecuba/id :hecuba/href :hecuba/type :hecuba/parent :hecuba/parent-href :hecuba/children-href}
                       (distinct (mapcat keys items)))]
    (let [DEBUG false]
      (html [:body
             [:h2 "Fields"]
             [:ul (for [k fields] [:li (csk/->snake_case_string (name k))])]
             [:h2 "Items"]
             [:table
              [:thead
               [:tr
                [:th "Name"]
                [:th "Parent"]
                [:th "Children"]
                (for [k fields] [:th (string/replace (csk/->Snake_case_string k) "_" " ")])
                (when DEBUG [:th "Debug"])]]
              [:tbody
               (for [p items]
                 [:tr
                  [:td [:a {:href (:hecuba/href p)} (:hecuba/name p)]]
                  [:td [:a {:href (:hecuba/parent-href p)} "Parent"]]
                  [:td [:a {:href (:hecuba/children-href p)} "Children"]]
                  (for [k fields] [:td (str (k p))])
                  (when DEBUG [:td (pr-str p)])])]]]))))

;; Now for the Liberator resources. Check the Liberator website for more
;; info: http://clojure-liberator.github.io/liberator/

(defn authorized? [typ querier]
  (fn [{{headers :headers route-params :route-params} :request}]
    (when-let [auth (get headers "authorization")]
      (infof "auth is %s" auth)
      (when-let [basic-creds (second (re-matches #"\QBasic\E\s+(.*)" auth))]
        (let [[user password] (->> (String. (DatatypeConverter/parseBase64Binary basic-creds) "UTF-8")
                                   (re-matches #"(.*):(.*)")
                                   rest)]
          (protocols/authorized? querier (merge route-params
                                                {:hecuba/user user :hecuba/password password :type typ})))))))

;; REST resource for items (plural) - .
(defresource items-resource [typ item-resource parent-resource child-index-p {:keys [querier commander]}]
  ;; acts as both an index of existing items and factory for new ones
  :allowed-methods #{:get :post}

  :exists? true                  ; This 'factory' resource ALWAYS exists
  :available-media-types base-media-types

  :authorized? (authorized? typ querier)

  :handle-ok                            ; do this upon a successful GET
  (fn [{{mime :media-type} :representation {routes :jig.bidi/routes route-params :route-params} :request}]
    (let [items
          (->>
           {:hecuba/type typ} ; form a 'where' clause starting with the type
           (merge route-params)         ; adding any route-params
           (items querier)              ; to query items
           ;; which are adorned with hrefs, throwing bidi's path-for all the kv pairs we have!
           (map #(-> (assoc %
                       :hecuba/href (apply path-for routes item-resource (apply concat %)))
                     (cond-> (:hecuba-parent %)
                             (assoc :hecuba/parent-href
                               (path-for routes parent-resource :hecuba/id (:hecuba/parent %))))
                     (cond-> (:hecuba/id %)
                             (assoc :hecuba/children-href
                               (apply path-for routes @child-index-p (apply concat (assoc % :hecuba/parent (:hecuba/id %)))))))))]

      (case mime
        "text/html" (render-items-html  items)
        ;; Liberator's default rendering of application/edn seems wrong
        ;; (it wraps the data in 'clojure.lang.PersistentArrayMap', so
        ;; we override it here.
        "application/edn" (pr-str (vec items))

        ;; The default is to let Liberator render our data
        items)))

  :post!                   ; enact the 'side-effect' of creating an item
  (fn [{{body :body route-params :route-params} :request}]
    {:hecuba/id                  ; add this entry to Liberator's context
     (upsert! commander
              (merge
               ;; Depending on the URI, the :hecuba/parent may already
               ;; be known. If so, it can be set (but the merge will
               ;; privilege any :hecuba/parent in the request body.
               route-params
               (-> body
                   ;; Prepare to read the body
                   io/reader (java.io.PushbackReader.)
                   ;; Read the body (can't repeat this so no transactions!)
                   edn/read io!
                   ;; Add the type prior to upserting
                   (assoc :hecuba/type typ))))})

  :handle-created                       ; do this upon a successful POST
  (fn [{{routes :jig.bidi/routes route-params :route-params} :request id :hecuba/id}]
    (ring-response      ; Liberator, we're returning an actual Ring map!
     ;; We must return the new resource path in the HTTP Location
     ;; header. Liberator doesn't do this for us, and clients might need
     ;; to know where the new resource is located.
     ;; See http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.2
     {:headers {"Location"
                (->> id                ; essentially the id
                     ;; but keep existing route params
                     (assoc route-params :hecuba/id)
                     ;; flatten into the keyword param form
                     (apply concat)
                     ;; ask bidi to return the Location URI
                     (apply path-for routes item-resource))}})))

(defn render-item-html
  "Render an item as HTML"
  [item]
  (html
   [:body
    [:h1 (:hecuba/name item)]
    (when-let [parent-href (:hecuba/parent-href item)]
      [:p [:a {:href parent-href} "Parent"]])
    (when-let [children-href (:hecuba/children-href item)]
      [:p [:a {:href children-href} "Children"]])

    [:pre (with-out-str
            (pprint item))]]))

;; REST resource for individual items.

;; Delivers REST verbs onto a single (particular) entity, like a
;; particular project or particular house.

;; The parent resource is the handler used by bidi to generate hrefs to
;; this resources parent container. For example, a house is owned by a
;; project.

;; The child resource is the reverse, used by bidi to generate hrefs to
;; any entities this item contains. The child resource is given as a
;; promise, since it cannot be known when constructing handlers top-down.

(defresource item-resource [typ parent-resource child-index-p {:keys [querier]}]
  :allowed-methods #{:get}
  :available-media-types base-media-types

  :authorized? (authorized? typ querier)

  :exists?  ; iff the item exists, we bind it to ::item in order to save
                                        ; an extra query later on - this is a common Liberator
                                        ; pattern
  (fn [{{{id :hecuba/id} :route-params body :body routes :jig.bidi/routes} :request}]
    (println "Does this item exist?" id)
    (when-let [itm (item querier id)]
      {::item (-> itm
                  (cond-> parent-resource
                          (assoc :hecuba/parent-href
                            (path-for routes parent-resource :hecuba/id (:hecuba/parent itm))))
                  (cond-> child-index-p
                          (assoc :hecuba/children-href
                            (path-for routes @child-index-p :hecuba/parent id))))}))

  :handle-ok
  (fn [{item ::item {mime :media-type} :representation}]
    (case mime
      "text/html" (render-item-html item)
      "application/edn" (pr-str item)
      ;; The default is to let Liberator render our data
      item)))
