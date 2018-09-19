* [Initialization](#initialization)
* [Creating an EventStore](#creating-an-eventstore)
* [Associations](#associations)
* [Storing Events](#storing-events)
  * [Selecting a Shard](#selecting-a-shard)
  * [Extracting Metadata](#extracting-metadata)
```clojure
(ns mariadb-event-store.core-test
  (:require [midje.sweet :refer :all]
            [mariadb-event-store.core :as es]
            [hikari-cp.core :as hcp]
            [clojure.java.jdbc :as jdbc]
            [pandect.algo.sha256 :as sha256])
  (:import (axiom.event_store EventStore
                              EventDomain))
  (:gen-class
   :name mariadb_event_store.EventStoreService
   :implements [axiom.event_store.EventStoreService]
   :state state
   :init init
   :constructors {[java.util.Map] []}))

'(def ds {:datasource (make-datasource dbconn)})

```
# Initialization

An `EventStoreService` requires the following properties to be set:
1. `host-pattern`: The pattern of the host-names of the database nodes, with `%` to be replaced by an ordinal number. For example, `foo-%.bar` represents `foo-0.bar`, `foo-1.bar`, etc.
2. `port`: The port number to be used.
3. `user`: The database user to be used.
4. `password`: The database password to be used.
5. `database`: The name of the database containing the events.
6. `num-shards`: The number of shards the database is separated to.
7. `num-replicas`: The number of replicas each shard has.

The overall number of database instances is requires to be `num-shards` times `num-replicas`.
```clojure
(fact
 (let [props (doto (java.util.HashMap.)
               (.put "host-pattern" "foo-%.bar")
               (.put "port" 1234)
               (.put "user" "me")
               (.put "password" "my-password")
               (.put "database" "my-dataabase")
               (.put "num-shards" 2)
               (.put "num-replicas" 3))
       state (es/-init props)]
   state => map?
   (def the-state state)))

```
The state contains the number of shards and replicas
```clojure
(fact
 (:num-shards the-state) => 2
 (:num-replicas the-state) => 3)

```
It also contains a `:data-sources` map, which maps `[shard
replica]` pairs into data-sources for the different database
instances. The data-sources are `delay`ed, meaning that connection
is made only on the first connection attampt.
```clojure
(fact
 (:data-sources the-state) => map?
 ((:data-sources the-state) [1 2]) => delay?
 (:datasource @((:data-sources the-state) [1 2])) => ..ds..
 (provided
  (hcp/make-datasource {:adapter "mariadb"
                        :port-number 1234
                        :username "me"
                        :password "my-password"
                        ;; node number = s*R+r
                        :server-name "foo-5.bar"
                        :database-name "my-dataabase"}) => ..ds..)
 (def the-datasource ..ds..))

```
# Creating an EventStore

An EventStoreService needs to implement a single method:
`.createEventStore`. This method takes an `EventDomain` to create
an application-specific `EventStore`.

An `EventDomain` is required to answer a few questions regarding
events, which are otherwise opaque objects as far as the
`EventStore` is concerned. The questions include extracting the
event's id, type, key, body and quantitative change, as well as
serialization and deserialization of the complete event.

Following is an example `EventDomain` implementation we will use
for the purposes of this document.
```clojure
(defn to-bytes [str]
  (.getBytes str))

(def my-domain
  (reify EventDomain
    (id [this ev]
      (:id ev))
    (type [this ev]
      (:type ev))
    (key [this ev]
      (-> ev :key to-bytes))
    (change [this ev]
      (:change ev))
    (body [this ev]
      (-> ev
          (dissoc :key)
          (dissoc :change)
          (dissoc :id)
          pr-str
          to-bytes))
    (serialize [this ev]
      ;; Obviously, not the best way to do this...
      (-> ev pr-str to-bytes))
    (deserialize [this ser]
      (-> ser String. read-string))))

```
To easily construct events within this domain, we define the
`event` function:
```clojure
(defn event [id type key change body]
  {:id id
   :type type
   :key key
   :change change
   :body body})

```
The domain must maintain that a deserialization of a serialized
event returns the same event.
```clojure
(fact
 (let [ev (event "1" "foo" "the-key" 1 [1 2 3])
       ser (.serialize my-domain ev)]
   (.deserialize my-domain ser) => ev))

```
Now, with an `EventDomain`, we can call `.createEventStore` to
instantiate an `EventStore`.

In the following example we use a record to mock the
`EventStoreService`, and call the underlying `-createEventStore`
function directly.
```clojure
(defrecord MockObj [state])
(fact
 (let [service (MockObj. the-state)]
   (def my-event-store (es/-createEventStore service my-domain))
   my-event-store => (partial instance? EventStore)))

```
An event-store can provide information about its configuration,
namely the number of shards and replicas.
```clojure
(fact
 (.numShards my-event-store) => 2
 (.replicationFactor my-event-store) => 3)


```
# Associations

Event types can be associated. When two types `a` and `b` are
associated, events of types `a` are _related_ to events of type `b`
with the same key, and vice versa.

The `.associate` method creates a bidirectional association between
two types (on a single replica of a single shard).
```clojure
(fact
 (.associate my-event-store "foo" "bar" 1 2) => nil
 (provided
  (jdbc/insert-multi! {:datasource the-datasource} :association [:tp1 :tp2]
                      [["foo" "bar"]
                       ["bar" "foo"]]) => irrelevant))

```
The `.getAssociation` method returns all the types associated with
a given type.
```clojure
(fact
 (.getAssociation my-event-store "foo" 1 2) => ["foo" "bar"]
 (provided
  (jdbc/query {:datasource the-datasource} ["SELECT tp2 FROM association WHERE tp1 = ?" "foo"]) => [{:tp2 "foo"} {:tp2 "bar"}]))

```
# Storing Events

Event storage is done in two pieces. First, the metadata (as
extracted by the `EventDomain`) is written to the `events` table,
and then body is written to one of the body tables.

## Selecting a Shard

Before we store an event, we need to know in which shard this event
resides.  `hash-to-shard` calculates, based on the keyhash and the
number of shards, a consistent shard number fot that key.
```clojure
(fact
 (let [key (str "KEY" (rand-int 100000))]
   (< (es/hash-to-shard (.getBytes key) 3) 3) => true))

```
Two identical keys will provide two identical shards:
```clojure
(fact
 (let [key (str "KEY" (rand-int 100000))]
   (= (es/hash-to-shard (.getBytes key) 100000) (es/hash-to-shard (.getBytes key) 100000)) => true))

```
However, different keys are most likely to provide different
shards, given that they do not accidentally collide.
```clojure
(fact
 (let [key1 (str "KEY" (rand-int 100000))
       key2 (str "KEY" (rand-int 100000))]
   (= (es/hash-to-shard (.getBytes key1) 100000) (es/hash-to-shard (.getBytes key2) 100000)) => false))

```
## Extracting Metadata

Event metadata is stored to the `events` table. The records
of this table are calculated by the `events-to-records` function.
```clojure
(fact
 (let [events (to-array [(event "id1" "type1" "key" 1 "body1")
                         (event "id2" "type2" "key" 1 "body2")])
       records (es/events-to-records my-domain events ..keyhash.. 1000)]
   (count records) => 2
   ;; The first two columns are the ID and type
   (->> records first (take 2)) => ["id1" "type1"]
   (->> records second (take 2)) => ["id2" "type2"]
   ;; Next is the keyhash, which is given as parameter
   (-> records first (nth 2)) => ..keyhash..
   (-> records second (nth 2)) => ..keyhash..
   ;; Next is the body hash as a byte array (we convert to string so we can compare)
   (let [expected (-> {:type "type1" :body "body1"} pr-str .getBytes sha256/sha256-bytes String.)]
     (-> records first (nth 3) String.) => expected)
   (let [expected (-> {:type "type2" :body "body2"} pr-str .getBytes sha256/sha256-bytes String.)]
     (-> records second (nth 3) String.) => expected)
   ;; Last are the change and timestamp.
   (->> records first (drop 4)) => [1 1000]
   (->> records second (drop 4)) => [1 1000]))

```
The function `event-content-records` returns records containing
`event_id` and `content` fields for all events.
```clojure
(fact
 (let [events [(event "id1" "type1" "key" 1 {:large (range 100)})
               (event "id2" "type2" "key" 1 {:small (range 2)})]
       records (es/event-content-records my-domain (to-array events))]
   (count records) => 2
   (map first records) => ["id1" "id2"]
   (map #(-> % second String. read-string) records) => events))

```
The method `.store` takes an array of events, assumed to have the
same key, and stores them within a single transaction to a replica
of the shard that corresponds to the key.

The full content of an event is placed in one of two tables:
`event_bodies`, for events for which the serialization is 256 bytes
and above, and `small_event_bodies`, for events which can be
serialized to less than 256 bytes. This separation is made for
performance reasons, to avoid using BLOB fields whenever possible.
```clojure
(fact
 (let [key-bytes (.getBytes "key")
       events (to-array [(event "id1" "type1" "key" 1 "body1")
                         (event "id2" "type2" "key" 1 "body2")])
       ;; Arbitrary short and long binary arrays
       small-content (-> (range 3) pr-str .getBytes)
       big-content (-> (range 300) pr-str .getBytes)]
   (.store my-event-store events 2 1000) => nil
   (provided
    (to-bytes "key") => key-bytes
    (sha256/sha256-bytes key-bytes) => ..keyhash..
    (es/hash-to-shard ..keyhash.. 2) => 1
    (es/events-to-records my-domain events ..keyhash.. 1000) => ..records..
    (es/event-content-records my-domain events) => [["id-short" small-content]
                                                    ["id-long" big-content]]
    (jdbc/insert-multi! {:datasource the-datasource} :events [:id :tp :keyhash :bodyhash :cng :ts]
                        ..records..) => irrelevant
    (jdbc/insert-multi! {:datasource the-datasource} :event_bodies [:event_id :content]
                        [["id-long" big-content]]) => irrelevant
    (jdbc/insert-multi! {:datasource the-datasource} :small_event_bodies [:event_id :content]
                        [["id-short" small-content]]) => irrelevant)))
```
