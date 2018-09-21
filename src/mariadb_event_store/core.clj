(ns mariadb-event-store.core
  (:require [clojure.string :as str]
            [hikari-cp.core :as hcp]
            [clojure.java.jdbc :as jdbc]
            [pandect.algo.sha256 :as sha256])
  (:import (axiom.event_store EventStore)))

(defn -init [props]
  (let [num-shards (.get props "num-shards")
        replication-factor (.get props "num-replicas")
        pattern (.get props "host-pattern")]
    {:num-shards num-shards
     :num-replicas replication-factor
     :data-sources (into {} (for [s (range num-shards)
                                  r (range replication-factor)]
                              [[s r] (delay {:datasource (hcp/make-datasource
                                                          {:adapter "mariadb"
                                                           :port-number (.get props "port")
                                                           :username (.get props "user")
                                                           :password (.get props "password")
                                                           :server-name (str/replace pattern "%" (str (+ (* s replication-factor) r)))
                                                           :database-name (.get props "database")})})]))}))

(defn hash-to-shard [hash num-shards]
  (-> (loop [s 0
             i 0]
        (if (< i 8)
          (recur (-> s
                     (* 256)
                     (+ (aget hash (mod i (alength hash)))))
                 (inc i))
          ;; else
          s))
      (mod num-shards)))

(defn events-to-records [domain events keyhash timestamp]
  (vec (for [i (range (alength events))]
         (let [ev (aget events i)
               body (.body domain ev)
               bodyhash (sha256/sha256-bytes body)]
           [(.id domain ev)
            (.type domain ev)
            keyhash
            bodyhash
            (.change domain ev)
            timestamp
            (.ttl domain ev)]))))

(defn event-content-records [domain events]
  (for [i (range (alength events))]
    (let [ev (aget events i)]
      [(.id domain ev)
       (.serialize domain ev)])))

(defn -createEventStore [this domain]
  (let [state (.state this)]
    (reify EventStore
      (numShards [this]
        (:num-shards state))
      (replicationFactor [this]
        (:num-replicas state))
      (associate [this type1 type2 shard replica]
        (let [ds (-> state :data-sources (get [shard replica]))]
          (jdbc/insert-multi! @ds :association [:tp1 :tp2]
                              [[type1 type2]
                               [type2 type1]])))
      (getAssociation [this type shard replica]
        (let [ds (-> state :data-sources (get [shard replica]))]
          (->> (jdbc/query @ds ["SELECT tp2 FROM association WHERE tp1 = ?" type])
               (map :tp2))))
      (store [this events replica timestamp]
        (let [ev (aget events 0)
              key (.key domain ev)
              keyhash (sha256/sha256-bytes key)
              shard (hash-to-shard keyhash (:num-shards state))
              ds (-> state :data-sources (get [shard replica]))
              content-records (event-content-records domain events)]
          (jdbc/insert-multi! @ds :events [:id :tp :keyhash :bodyhash :cng :ts :ttl]
                              (events-to-records domain events keyhash timestamp))
          (jdbc/insert-multi! @ds :event_bodies [:event_id :content]
                              (vec (->> content-records
                                        (filter #(>= (alength (second %)) 256)))))
          (jdbc/insert-multi! @ds :small_event_bodies [:event_id :content]
                              (vec (->> content-records
                                        (filter #(< (alength (second %)) 256)))))))
      (get [this type key replica since now]
        (let [shard (hash-to-shard key (:num-shards state))
              ds (-> state :data-sources (get [shard replica]))]
          (->> (jdbc/query @ds
                           ["SELECT content FROM events_with_bodies WHERE ts >= ? AND (ttl IS NULL OR ttl >= ?)" since now])
               (map :content)
               (map #(.deserialize domain %)))))
      (getRelated [this ev replica since now]
        (let [shard (-> (.key domain ev)
                        (sha256/sha256-bytes)
                        (hash-to-shard (:num-shards state)))
              ds (-> state :data-sources (get [shard replica]))]
          (->> (jdbc/query @ds
                           ["SELECT content FROM related_events WHERE ts >= ? AND (ttl IS NULL OR ttl >= ?)" since now])
               (map :content)
               (map #(.deserialize domain %)))))
      (scanKeys [this shard replica]
        (let [ds (-> state :data-sources (get [shard replica]))]
          (->> (jdbc/query @ds
                           ["SELECT DISTINCT keyhash FROM events"])
               (map :keyhash))))
      (maintenance [this shard replica now]
        (let [ds (-> state :data-sources (get [shard replica]))]
          (jdbc/execute! @ds ["CALL compaction(?)" now])))
      (pruneType [this type shard replica]
        (let [ds (-> state :data-sources (get [shard replica]))]
          (jdbc/execute! @ds ["DELETE FROM association WHERE tp1 = ? OR tp2 = ?" type type])
          (jdbc/execute! @ds ["DELETE FROM events WHERE tp = ?" type]))))))



