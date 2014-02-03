(ns kixi.hecuba.web
  (:require
   jig
   [kixi.hecuba.web.resources :refer (items-resource item-resource)]
   kixi.hecuba.web.device
   kixi.hecuba.web.messages
   [kixi.hecuba.data :as data]
   [jig.util :refer (get-dependencies satisfying-dependency)]
   [jig.bidi :refer (add-bidi-routes)]
   [bidi.bidi :refer (match-route path-for ->Resources ->Redirect ->Alternates)]
   [ring.middleware.params :refer (wrap-params)]
   [clojure.java.io :as io]
   [clojure.tools.logging :refer :all]
   [liberator.core :refer (resource defresource)])
  (:import (jig Lifecycle)))

(def base-media-types ["application/json"])

(defn index [req]
  {:status 200 :body (slurp (io/resource "sb-admin/index.html"))})

(defn tables [req]
  {:status 200 :body (slurp (io/resource "sb-admin/tables.html"))})

(defn chart [req]
  {:status 200 :body (slurp (io/resource "hecuba/chart.html"))})

(defn maps [req]
  {:status 200 :body (slurp (io/resource "hecuba/map.html"))})

(defn counters [req]
  {:status 200 :body (slurp (io/resource "hecuba/counters.html"))})

(defn readings [req]
  {:status 200 :body (slurp (io/resource "reading.html"))})

;; TODO querier and commander and simply here to be passed through to
;; the resources, there is not much value in separating them out into
;; individual args, so put them in a map.
(defn make-handlers
  "This is a difficult function to read and implement. The purpose of
  this function is to return a map of REST handlers, keyed with the
  keywords given in pairs. The pairs argument is a sequence of keyword
  pairs, each pair denoting the plural and singular of a given
  entity (e.g. [:projects :project]. The sequence of pairs represents a
  hierarchy of entities, ordered parent first."
  [pairs opts]
  ;; We loop down the hierarchy, as defined by 'pairs'
  (loop [[plural singular :as pair] (first pairs)
         remaining (next pairs)
         parent-resource nil      ; nil the root entity in the hierarchy
         unknown-child-index (promise) ; see below
         handlers {}]
    (if-not pair
      handlers ; return at the end
      (let [
            ;; item-resource needs to know the child-resource this is a
            ;; catch-22, we can't know what the child resource handler
            ;; is going to be, but we 'promise' that we will.
            child-index (when remaining (promise))

            ;; Specific here is in contrast to an 'index' resource which
            ;; provides a resource onto multiple items.
            specific-resource (item-resource singular parent-resource child-index opts)
            index-resource (items-resource singular specific-resource parent-resource child-index opts)]

        ;; Now we have created our child resource we can make good on our promise
        (deliver unknown-child-index index-resource)

        (recur (first remaining) (next remaining) specific-resource child-index
               (assoc handlers plural index-resource singular specific-resource))))))

(def sha1-regex #"[a-f0-9]+")

(defn make-routes [producer-config handlers {:keys [querier commander]}]
  ["/"
   [["" (->Redirect 307 tables)]
    ["index.html" index]
    ["tables.html" tables]
    ["chart.html" chart]
    ["counters.html" counters]
    ["map.html" maps]

    (kixi.hecuba.web.device/create-routes producer-config)
    (kixi.hecuba.web.messages/create-routes querier commander)

    ;; Programmes
    ["programmes/" (:programmes handlers)]
    ["programmes" (->Redirect 307 (:programmes handlers))]
    [["programmes/" [sha1-regex :hecuba/id]] (:programme handlers)]
    [["programmes/" [sha1-regex :hecuba/parent] "/projects"] (:projects handlers)]

    ;; Projects
    ["projects/" (:projects handlers)]
    ["projects" (->Redirect 307 (:projects handlers))]
    [["projects/" [sha1-regex :hecuba/id]] (:project handlers)]
    [["projects/" [sha1-regex :hecuba/parent] "/properties"] (:properties handlers)]

    ;; Properties, with an 'X' suffix to avoid conflicting with Anna's
    ;; work until we integrate this.
    ;; Eventually these routes can be generated from the keyword pairs.
    ["propertiesX/" (:properties handlers)]
    ["propertiesX" (->Redirect 307 (:properties handlers))]
    [["propertiesX/" [sha1-regex :hecuba/id]] (:property handlers)]
    [["propertiesX/" [sha1-regex :hecuba/parent] "/devices"] (:devices handlers)]

    ["devices/" (:devices handlers)]
    ["devices" (->Redirect 307 (:devices handlers))]
    [["devices/" [sha1-regex :hecuba/id]] (:device handlers)]

    ["hecuba-js/react.js" (->Resources {:prefix "sb-admin/"})]
    ["" (->Resources {:prefix "sb-admin/"})]
    ]])

(deftype Website [config]
  Lifecycle
  (init [_ system] system)
  (start [_ system]
    (let [commander (:commander system)
          querier (:querier system)
          handlers (make-handlers data/hierarchy {:querier querier :commander commander})
          routes (make-routes (first (:kixi.hecuba.kafka/producer-config (:hecuba/kafka system)))
                              handlers {:querier querier :commander commander})]
      (-> system
          (add-bidi-routes config routes)
          (update-in [:handlers] merge handlers))))
  (stop [_ system] system))
