```clojure
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
                         [brosenan/injectthedriver "0.0.5"]]
                       {}
                       '[(ns main-test
                           (:use midje.sweet)
                           (:import (injectthedriver DriverFactory)
                                    (axiom.event_store EventStoreService
                                                       EventDomain)))
                         (def ess (DriverFactory/createDriverFor EventStoreService))
                         (prn (.state ess))
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
                          (doseq [i (range 10)
                                  r (range 2)]
                            (.store es (to-array [(event (str "ev" i) "foo" (str i) 1 {:value (* i 2)})]) r (+ 1000 i)))
                          (.get es (.getBytes "3") 1 1000 2000) => [(event "ev3" "foo" "3" 1 {:value 6})]
                          (.get es (.getBytes "3") 1 1100 2000) => [])])
                      (lk/update-container :test lku/inject-driver EventStoreService event-store)
                      ;; For the purpose of the test, we need to
                      ;; provide persistent volumes to be used by the
                      ;; database instances.
                      (update :$additional concat
                              (for [i (range 6)]
                                {:kind :PersistentVolume
                                 :apiVersion :v1
                                 :metadata {:name (str "store-and-get-vol" i)
                                            :labels {:type :local}}
                                 :spec {:storageClassName :manual
                                        :persistentVolumeReclaimPolicy "Recycle"
                                        :capacity {:storage "200Mi"}
                                        :accessModes ["ReadWriteOnce"]
                                        :hostPath {:path (str "/mnt/data" i)}}})))))))

(fact
 :kube
 (-> (lk/injector)
     (mes-lk/module)
     (test-module)
     (lk/standard-descs)
     (lkt/kube-tests "mdb-es")) => "")
```

