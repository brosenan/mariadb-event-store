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
                                              :replication-factor 3}}
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
                         (def domain
                           (reify EventDomain))
                         (def es (.createEventStore ess domain))
                         (fact
                          (.numShards es) => 2
                          (.replicationFactor es) => 3)])
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

