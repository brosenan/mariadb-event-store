(ns mariadb-event-store.core-test
  (:require [midje.sweet :refer :all]
            [mariadb-event-store.core :as es])
  (:import (axiom.event_store EventStore
                              Event))
  (:gen-class
   :name mariadb_event_store.EventStore
   :implements [axiom.event_store.EventStore]
   :state state
   :init init
   :constructors {[java.util.Map] []}))

;; # Initialization

;; An `EventStore` requires the following properties to be set:
;; 1. `host-pattern`: The pattern of the host-names of the database nodes, with `%` to be replaced by an ordinal number. For example, `foo-%.bar` represents `foo-0.bar`, `foo-1.bar`, etc.
;; 2. `port`: The port number to be used.
;; 3. `num-shards`: The number of shards the database is separated to.
;; 4. `num-replicas`: The number of replicas each shard has.

;; The overall number of database instances is requires to be `num-shards` times `num-replicas`.
(fact
 (let [props (doto (java.util.HashMap.)
               (.put "host-pattern" "foo-%.bar")
               (.put "port" 1234)
               (.put "num-shards" 5)
               (.put "num-replicas" 3))
       state (es/-init props)]
   state => map?
   (:port state) => 1234
   (:num-shards state) => 5
   (:num-replicas state) => 3))

;; As part of the state, the function `:hostname` takes a shard number
;; and a replica number, and returns a host name for the relevant
;; database. For shard `s` and replica `r`, the node number is
;; `n=s*R+r`, where `R` is the replication-factor (`num-replicas`).
(fact
 (let [props (doto (java.util.HashMap.)
               (.put "host-pattern" "foo-%.bar")
               (.put "port" 1234)
               (.put "num-shards" 5)
               (.put "num-replicas" 3))
       state (es/-init props)]
   ((:hostname state) 1 2) => "foo-5.bar"))
