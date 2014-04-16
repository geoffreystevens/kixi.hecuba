(ns kixi.hecuba.api.entities
  (:require
   [bidi.bidi :as bidi]
   [cheshire.core :as json]
   [clojure.tools.logging :as log]
   [kixi.hecuba.protocols :as hecuba]
   [kixi.hecuba.security :as sec]
   [kixi.hecuba.webutil :as util]
   [kixi.hecuba.webutil :refer (decode-body authorized? stringify-values sha1-regex)]
   [liberator.core :refer (defresource)]
   [liberator.representation :refer (ring-response)]))

(defn index-post! [commander querier ctx]
  (let [request           (-> ctx :request)
        entity            (-> request decode-body stringify-values)
        project-id        (-> entity :project-id)
        property-code     (-> entity :property-code)
        [username _]      (sec/get-username-password request querier)
        user-id           (-> (hecuba/items querier :user {:username username}) first :id)]
    (when (and project-id property-code)
      (when-not (empty? (first (hecuba/items querier :project {:id project-id})))
        {:entity-id (hecuba/upsert! commander :entity (-> entity
                                                          (assoc :user-id user-id)
                                                          (dissoc :device-ids)))}))))

(defn index-handle-created [handlers ctx]
  (let [request (:request ctx)
        routes  (:modular.bidi/routes ctx)
        id      (:entity-id ctx)]
    (if-not (empty? id)
      (let [location (bidi/path-for routes (:entity @handlers) :entity-id id)]
        (when-not location
          (throw (ex-info "No path resolved for Location header"
                          {:entity-id id})))
        (ring-response {:headers {"Location" location}
                        :body (json/encode {:location location
                                            :status "OK"
                                            :version "4"})}))
      (ring-response {:status 422
                      :body "Provide valid projectId and propertyCode."}))))

(defn resource-exists? [querier ctx]
  (let [id (get-in ctx [:request :route-params :entity-id])]
    (when-let [item (hecuba/item querier :entity id)]
      {::item item})))

(defn resource-handle-ok [querier ctx]
  (let [request      (:request ctx)
        route-params (:route-params request)
        ids          (map :id (hecuba/items querier :device route-params))
        item         (assoc (::item ctx) :device-ids ids)]
      (util/render-item request item)))

(defn resource-put! [commander querier ctx]
  (let [request      (:request ctx)
        entity       (-> request decode-body stringify-values)
        entity-id    (-> entity :entity-id)
        [username _] (sec/get-username-password request querier)
        user-id      (-> (hecuba/items querier :user {:username username}) first :id)]
      (hecuba/upsert! commander :entity (-> entity
                                            (assoc :user-id user-id
                                                   :id entity-id)
                                            (dissoc :device-ids)))))

(defn [commander ctx]
  (hecuba/delete! commander :entity (get-in ctx [::item :id])))

(defresource index [{:keys [commander querier]} handlers]
  :allowed-methods #{:post}
  :available-media-types #{"application/json"}
  :known-content-type? #{"application/json"}
  :authorized? (authorized? querier :entity)
  :post! (partial index-post! commander querier)
  :handle-created (partial index-handle-created ))

(defresource resource [{:keys [commander querier]} handlers]
  :allowed-methods #{:get :delete :put}
  :available-media-types #{"application/json"}
  :authorized? (authorized? querier :entity)
  :exists? (partial resource-exists? querier)
  :handle-ok (partial resource-handle-ok querier)
  :put! (partial resource-put! commander)
  :respond-with-entity? (constantly true)
  :delete! (partial resource-delete! commander))