* [Initialization](#initialization)
* [Creating an EventStore](#creating-an-eventstore)
* [Associations](#associations)
* [Storing Events](#storing-events)
  * [Selecting a Shard](#selecting-a-shard)
  * [Extracting Metadata](#extracting-metadata)
  * [Extracting Content](#extracting-content)
  * [The `.store` Method](#the-`.store`-method)
* [Event Retrieval](#event-retrieval)
* [Scanning](#scanning)
* [Compaction](#compaction)
* [Removal of Types](#removal-of-types)
* [Error Handling](#error-handling)
```clojure
(ns mariadb-event-store.core-test
  (:require [midje.sweet :refer :all]
            [mariadb-event-store.core :as es]
            [hikari-cp.core :as hcp]
            [clojure.java.jdbc :as jdbc]
            [pandect.algo.sha256 :as sha256])
  (:import (axiom.event_store EventStore
                              EventDomain)
           (java.io IOException)))

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
       [arglist state] (es/init props)]
   arglist => []
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

(defn from-bytes [bytes]
  (-> bytes String. read-string))

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
          (dissoc :ttl)
          pr-str
          to-bytes))
    (ttl [this ev]
      (:ttl ev))
    (serialize [this ev]
      ;; Obviously, not the best way to do this...
      (-> ev pr-str to-bytes))
    (deserialize [this ser]
      (from-bytes ser))))

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
`EventStoreService`, and call the underlying `createEventStore`
function directly.
```clojure
(defrecord MockObj [state])
(fact
 (let [service (MockObj. the-state)]
   (def my-event-store (es/createEventStore service my-domain))
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
                         (-> (event "id2" "type2" "key" 1 "body2")
                             (assoc :ttl 1010))])
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
   ;; Last are the change, timestamp and tts (if defined), which
   ;; appears twice. Once for the insertion, and once for the update,
   ;; in case the event already exists.
   (->> records first (drop 4)) => [1 1000 nil nil]
   (->> records second (drop 4)) => [1 1000 1010 1010]))

```
## Extracting Content

The function `event-content-records` returns records containing
`event_id` and `content` fields for all events. `content` is
provided twice, once for the insertion, and once for the update, if
the event already exists.
```clojure
(fact
 (let [events [(event "id1" "type1" "key" 1 {:large (range 100)})
               (event "id2" "type2" "key" 1 {:small (range 2)})]
       records (es/event-content-records my-domain (to-array events))]
   (count records) => 2
   (map first records) => ["id1" "id2"]
   (map #(-> % second String. read-string) records) => events
   (map #(-> % (nth 2) String. read-string) records) => events))

```
## The `.store` Method

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
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO events (id, tp, keyhash, bodyhash, cng, ts, ttl) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ttl = ?"
                    ..records..]
                   {:multi? true}) => irrelevant
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO event_bodies (event_id, content) VALUES (?, ?) ON DUPLICATE KEY UPDATE content = ?"
                    [["id-long" big-content]]]
                   {:multi? true}) => irrelevant
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO small_event_bodies (event_id, content) VALUES (?, ?) ON DUPLICATE KEY UPDATE content = ?"
                    [["id-short" small-content]]]
                   {:multi? true}) => irrelevant)))

```
Insertion to either content tables is only done if there is
something to insert.
```clojure
(fact
 (let [key-bytes (.getBytes "key")
       events (to-array [(event "id1" "type1" "key" 1 "body1")])
       small-content (-> (range 3) pr-str .getBytes)]
   (.store my-event-store events 2 1000) => nil
   (provided
    (to-bytes "key") => key-bytes
    (sha256/sha256-bytes key-bytes) => ..keyhash..
    (es/hash-to-shard ..keyhash.. 2) => 1
    (es/events-to-records my-domain events ..keyhash.. 1000) => ..records..
    (es/event-content-records my-domain events) => [["id-short" small-content]]
    (jdbc/execute! {:datasource the-datasource} 
                   ["INSERT INTO events (id, tp, keyhash, bodyhash, cng, ts, ttl) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ttl = ?"
                    ..records..]
                   {:multi? true}) => irrelevant
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO small_event_bodies (event_id, content) VALUES (?, ?) ON DUPLICATE KEY UPDATE content = ?"
                    [["id-short" small-content]]]
                   {:multi? true}) => irrelevant)))

(fact
 (let [key-bytes (.getBytes "key")
       events (to-array [(event "id1" "type1" "key" 1 "body1")])
       big-content (-> (range 300) pr-str .getBytes)]
   (.store my-event-store events 2 1000) => nil
   (provided
    (to-bytes "key") => key-bytes
    (sha256/sha256-bytes key-bytes) => ..keyhash..
    (es/hash-to-shard ..keyhash.. 2) => 1
    (es/events-to-records my-domain events ..keyhash.. 1000) => ..records..
    (es/event-content-records my-domain events) => [["id-long" big-content]]
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO events (id, tp, keyhash, bodyhash, cng, ts, ttl) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ttl = ?"
                    ..records..]
                   {:multi? true}) => irrelevant
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO event_bodies (event_id, content) VALUES (?, ?) ON DUPLICATE KEY UPDATE content = ?"
                    [["id-long" big-content]]]
                   {:multi? true}) => irrelevant)))

```
# Event Retrieval

The `.get` method takes a type ane a key, and retrieves all events
under them (from the specified replica). It retrieves events dating
from the specified timestamp and up, and omits events for which the
TTL is below the specified current time.

`.get` needs to work with `.scanKeys`, and need to agree with it on
how keys are represented. In this implementation, for the purpose
of `.scanKeys` and `.get`, the keys are represented _using their
hashes_.
```clojure
(fact
 (let [keyhash (.getBytes "the key hash")
       bin1 (.getBytes "some-binary-1")
       bin2 (.getBytes "some-binary-2")]
   (.get my-event-store "mytype" keyhash 2 1000 2000) => [..event1.. ..event2..]
   (provided
    (es/hash-to-shard keyhash 2) => 1
    (jdbc/query {:datasource the-datasource}
                ["SELECT content FROM events_with_bodies WHERE tp = ? AND keyhash = ? AND ts >= ? AND (ttl IS NULL OR ttl >= ?)" "mytype" keyhash 1000 2000])
    => [{:content bin1}
        {:content bin2}]
    (from-bytes bin1) => ..event1..
    (from-bytes bin2) => ..event2..)))

```
The `.getRelated` method takes an event and returns all _related_
events, i.e., events of associated types with the same key.
```clojure
(fact
 (let [key (.getBytes "key")
       keyhash (.getBytes "keyhash")
       bin1 (.getBytes "some-binary-1")
       bin2 (.getBytes "some-binary-2")]
   (.getRelated my-event-store (event "id" "type" "key" 1 "body") 2 1000 2000) => [..event1.. ..event2..]
   (provided
    ;; Determine the shard
    (to-bytes "key") => key
    (sha256/sha256-bytes key) => keyhash
    (es/hash-to-shard keyhash 2) => 1
    ;; Make the query
    (jdbc/query {:datasource the-datasource}
                ["SELECT content FROM related_events WHERE ts >= ? AND (ttl IS NULL OR ttl >= ?)" 1000 2000])
    => [{:content bin1}
        {:content bin2}]
    (from-bytes bin1) => ..event1..
    (from-bytes bin2) => ..event2..)))

```
# Scanning

`.scanKeys` returns all the unique keys stored in a shard (as
viewed by a specific replica). The interface leaves it up to the
implementation to determine the representation of the key, so long
that it agrees with `.get`, i.e., so long that it gives us access
to all events of a certain types stored within a shard.  Our
implementation uses the key-hash.
```clojure
(fact
 (.scanKeys my-event-store 1 2) => [..kh1.. ..kh2..]
 (provided
  (jdbc/query {:datasource the-datasource}
              ["SELECT DISTINCT keyhash FROM events"])
  => [{:keyhash ..kh1..}
      {:keyhash ..kh2..}]))

```
# Compaction

Databases need to be occasionally compacted. In our case,
compaction entails two operations. First, we need to remove all
events that cancel each other, i.e., for which the sum of the
quantitative change is 0. Second, we would like to remove all
events for which the TTL has expired.

The stored procedure `compaction` handles both tasks. It takes the
current time as parameter.

The method `.maintenance`, taking a shard, replica and the current
time, handles compaction.
```clojure
(fact
 (.maintenance my-event-store 1 2 2000) => nil
 (provided
  (jdbc/execute! {:datasource the-datasource}
                 ["CALL compaction(?)" 2000]) => irrelevant))

```
# Removal of Types

Types that are not in use anymore (e.g., after a software upgrade),
can be removed using the `.pruneType` method. This removes them
from the entire state, including:
1. The `association` table, where records are removed when the type is either in the first or second position.
2. The `events` table,
```clojure
(fact
 (.pruneType my-event-store "old-type" 1 2) => nil
 (provided
  (jdbc/execute! {:datasource the-datasource}
                 ["DELETE FROM association WHERE tp1 = ? OR tp2 = ?" "old-type" "old-type"]) => irrelevant
  (jdbc/execute! {:datasource the-datasource}
                 ["DELETE FROM events WHERE tp = ?" "old-type"]) => irrelevant))


```
# Error Handling

Every database access can go wrong, throwing an exception. The
interface requires throwing an `IOException` if anything goes
wrong. Here we simulate these scenarios.

The `.associate` method makes an insertion to the database, which
can fail.
```clojure
(fact
 (.associate my-event-store "foo" "bar" 1 2) => (throws IOException)
 (provided
  (jdbc/insert-multi! {:datasource the-datasource} :association [:tp1 :tp2]
                      [["foo" "bar"]
                       ["bar" "foo"]]) =throws=> (Exception. "something went wrong")))

```
The `.getAssociation` method makes a query.
```clojure
(fact
 (.getAssociation my-event-store "foo" 1 2) => (throws IOException)
 (provided
  (jdbc/query {:datasource the-datasource} ["SELECT tp2 FROM association WHERE tp1 = ?" "foo"])
  =throws=> (Exception. "something went wrong")))

```
`.store` makes multiple insertions.
```clojure
(fact
 (let [key-bytes (.getBytes "key")
       events (to-array [(event "id1" "type1" "key" 1 "body1")
                         (event "id2" "type2" "key" 1 "body2")])
       ;; Arbitrary short and long binary arrays
       small-content (-> (range 3) pr-str .getBytes)
       big-content (-> (range 300) pr-str .getBytes)]
   (.store my-event-store events 2 1000) => (throws IOException)
   (provided
    (to-bytes "key") => key-bytes
    (sha256/sha256-bytes key-bytes) => ..keyhash..
    (es/hash-to-shard ..keyhash.. 2) => 1
    (es/events-to-records my-domain events ..keyhash.. 1000) => ..records..
    (es/event-content-records my-domain events) => [["id-short" small-content]
                                                    ["id-long" big-content]]
    (jdbc/execute! {:datasource the-datasource}
                   ["INSERT INTO events (id, tp, keyhash, bodyhash, cng, ts, ttl) VALUES (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE ttl = ?"
                    ..records..]
                   {:multi? true}) =throws=> (Exception. "something went wrong"))))

```
`.get` and `.getRelated` each make queries.
```clojure
(fact
 (let [keyhash (.getBytes "the key hash")
       bin1 (.getBytes "some-binary-1")
       bin2 (.getBytes "some-binary-2")]
   (.get my-event-store "mytype" keyhash 2 1000 2000) => (throws IOException)
   (provided
    (es/hash-to-shard keyhash 2) => 1
    (jdbc/query {:datasource the-datasource}
                ["SELECT content FROM events_with_bodies WHERE tp = ? AND keyhash = ? AND ts >= ? AND (ttl IS NULL OR ttl >= ?)" "mytype" keyhash 1000 2000])
    =throws=> (Exception. "something went wrong"))))

(fact
 (let [key (.getBytes "key")
       keyhash (.getBytes "keyhash")
       bin1 (.getBytes "some-binary-1")
       bin2 (.getBytes "some-binary-2")]
   (.getRelated my-event-store (event "id" "type" "key" 1 "body") 2 1000 2000) => (throws IOException)
   (provided
    ;; Determine the shard
    (to-bytes "key") => key
    (sha256/sha256-bytes key) => keyhash
    (es/hash-to-shard keyhash 2) => 1
    ;; Make the query
    (jdbc/query {:datasource the-datasource}
                ["SELECT content FROM related_events WHERE ts >= ? AND (ttl IS NULL OR ttl >= ?)" 1000 2000])
    =throws=> (Exception. "something went wrong"))))

```
`.scanKeys` makes a query.
```clojure
(fact
 (.scanKeys my-event-store 1 2) => (throws IOException)
 (provided
  (jdbc/query {:datasource the-datasource}
              ["SELECT DISTINCT keyhash FROM events"])
  =throws=> (Exception. "something went wrong")))

```
`.maintenance` calls a stored procedure.
```clojure
(fact
 (.maintenance my-event-store 1 2 2000) => (throws IOException)
 (provided
  (jdbc/execute! {:datasource the-datasource}
                 ["CALL compaction(?)" 2000]) =throws=> (Exception. "something went wrong")))

```
`.pruneType` makes two deletions.
```clojure
(fact
 (.pruneType my-event-store "old-type" 1 2) => (throws IOException)
 (provided
  (jdbc/execute! {:datasource the-datasource}
                 ["DELETE FROM association WHERE tp1 = ? OR tp2 = ?" "old-type" "old-type"])
  =throws=> (Exception. "something went wrong")))
```

