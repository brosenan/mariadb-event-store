(ns mariadb-event-store.lk-test
  (:require [mariadb-event-store.lk :as mes-lk]
            [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [lambdakube.testing :as lkt]
            [midje.sweet :refer :all])
  (:import (axiom.event_store EventStoreService)))

(defn test-module [$]
  (-> $
      (lkt/test :store-and-get
                {:mariadb-event-store-config {:num-shards 2
                                              :replication-factor 2}}
                [:axiom-event-store]
                (fn [event-store]
                  (-> (lk/pod :test {})
                      (lku/add-midje-container
                       :test
                       '[[org.clojure/clojure "1.9.0"]
                         [brosenan/event_store "0.0.4"]
                         [brosenan/injectthedriver "0.0.5"]
                         [pandect "0.6.1"]]
                       {}
                       '[(ns main-test
                           (:use midje.sweet)
                           (:require
                            [pandect.algo.sha256 :as sha256])
                           (:import (injectthedriver DriverFactory)
                                    (axiom.event_store EventStoreService
                                                       EventDomain)))
                         (def ess (DriverFactory/createDriverFor EventStoreService))
                         (def domain
                           (reify EventDomain
                             (id [this ev]
                               (:id ev))
                             (type [this ev]
                               (:type ev))
                             (key [this ev]
                               (-> ev :key .getBytes))
                             (change [this ev]
                               (:change ev))
                             (body [this ev]
                               (-> ev
                                   (dissoc :id)
                                   (dissoc :change)
                                   (dissoc :key)
                                   (pr-str)
                                   .getBytes))
                             (ttl [this ev]
                               (:ttl ev))
                             (serialize [this ev]
                               (-> ev (pr-str) .getBytes))
                             (deserialize [this ser]
                               (-> ser String. read-string))))
                         (def es (.createEventStore ess domain))
                         (defn event [id type key change data]
                           {:id id
                            :type type
                            :key key
                            :change change
                            :data data})
                         (fact
                          (.numShards es) => 2
                          (.replicationFactor es) => 2)
                         (fact
                          ;; We store 10 events on all (2) replicas.
                          (doseq [i (range 10)
                                  r (range 2)]
                            (.store es (to-array [(event (str "ev" i) "foo" (str i) 1 {:value (* i 2)})]) r (+ 1000 i))))
                         (fact
                          ;; Based on the since parameter, we should
                          ;; either get a specific event by its key,
                          ;; or none.
                          (.get es "foo" (-> "3" .getBytes sha256/sha256-bytes) 1 0 2000) => [(event "ev3" "foo" "3" 1 {:value 6})]
                          (.get es "foo" (-> "3" .getBytes sha256/sha256-bytes) 1 1100 2000) => [])
                         (fact
                          ;; Now we re-store ev3, this time with some
                          ;; TTL.
                          (doseq [r (range 2)]
                            (.store es (to-array [(-> (event "ev3" "foo" "3" 1 {:value 6})
                                                      (assoc :ttl 1500))]) r 1200)))
                         (fact
                          ;; Now, if we get the event at time 2000, we
                          ;; should not see it, but earlier than its
                          ;; TTL, we should.
                          (.get es "foo" (-> "3" .getBytes sha256/sha256-bytes) 1 0 2000) => []
                          (.get es "foo" (-> "3" .getBytes sha256/sha256-bytes) 1 0 1400) => [(-> (event "ev3" "foo" "3" 1 {:value 6})
                                                                                                  (assoc :ttl 1500))])
                         (fact
                          ;; Let's define bar to be associated with foo
                          (doseq [s (range 2)
                                  r (range 2)]
                            (.associate es "foo" "bar" s r)))
                         (fact
                          ;; Now if we have a bar event, it is related
                          ;; to the corresponding foo events.
                          (let [bar-event (event "bar4" "bar" "4" 1 {:large-content (range 1000)})]
                            (doseq [r (range 2)]
                              (.store es (to-array [bar-event]) r 1300))
                            ;; A single foo event is related to this one, and vice versa
                            (let [foo-events (.getRelated es bar-event 1 0 2000)]
                              foo-events => [(event "ev4" "foo" "4" 1 {:value 8})]
                              (.getRelated es (first foo-events) 0 0 2000) => [bar-event])))
                         (fact
                          ;; Scanning all shards together should give
                          ;; us 10 distinct values.
                          (let [keys (->> (range 2)
                                          (mapcat #(.scanKeys es % 0)))]
                            (count keys) => 10
                            ;; Each key is usable with .get
                            (doseq [key keys]
                              (count (.get es "foo" key 1 0 1400)) => 1)))
                         (fact
                          ;; Now let us add deletion events (:change =
                          ;; -1) for all foo events, and see how
                          ;; compaction removes them.
                          (doseq [i (range 10)
                                  r (range 2)]
                                 (.store es (to-array [(event (str "del" i) "foo" (str i) -1 {:value (* i 2)})]) r (+ 1100 i)))
                          ;; We still have ten distinct keys stored.
                          (->> (range 2)
                               (mapcat #(.scanKeys es % 0))
                               (count)) => 10
                          ;; Now we compact the database
                          (doseq [s (range 2)
                                  r (range 2)]
                            (.maintenance es s r 2000))
                          ;; After compaction, we should only see one
                          ;; key: the key 4 of the "bar" event we
                          ;; created.
                          (->> (range 2)
                               (mapcat #(.scanKeys es % 0))
                               (mapcat #(.get es "foo" % 0 0 2000))) => []
                          (->> (range 2)
                               (mapcat #(.scanKeys es % 0))
                               (mapcat #(.get es "bar" % 0 0 2000))) => [(event "bar4" "bar" "4" 1 {:large-content (range 1000)})])
                         (fact
                          ;; If we prune the "bar" type, the event we
                          ;; see above would be erased.
                          (doseq [s (range 2)
                                  r (range 2)]
                            (.pruneType es "bar" s r))
                          (->> (range 2)
                               (mapcat #(.scanKeys es % 0))
                               (mapcat #(.get es "bar" % 0 0 2000))) => [])])
                      (lk/update-container :test lku/inject-driver EventStoreService event-store)
                      (lk/add-init-container :wait-for-db "busybox"
                                             {:command ["sh" "-c" "while ! sh -e /etc/wait-for-db/scan-dbs.sh; do sleep 1; done"]})
                      (lk/add-files-to-container :wait-for-db :scan-dbs "/etc/wait-for-db"
                                                 {"scan-dbs.sh" "for i in $(seq 0 3); do nc -z mdb-es-$i.mariadb-event-store 3306; done"})
                      ;; For the purpose of the test, we need to
                      ;; provide persistent volumes to be used by the
                      ;; database instances.
                      (update :$additional concat
                              (for [i (range 4)]
                                {:kind :PersistentVolume
                                 :apiVersion :v1
                                 :metadata {:name (str "store-and-get-vol" i)
                                            :labels {:type :local}}
                                 :spec {:storageClassName :manual
                                        :persistentVolumeReclaimPolicy "Delete"
                                        :capacity {:storage "200Mi"}
                                        :accessModes ["ReadWriteOnce"]
                                        :hostPath {:path (str "/mnt/data" i)}}})))))))

(fact
 :kube
 (lku/with-docker-repo
   (-> (lk/injector)
       (mes-lk/module)
       (test-module)
       (lk/standard-descs)
       (lkt/kube-tests "mdb-es"))) => "")
