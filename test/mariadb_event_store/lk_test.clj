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
                                    (axiom.event_store EventStoreService)))
                         (fact
                          (DriverFactory/createDriverFor EventStoreService))])
                      (lk/update-container :test lku/inject-driver EventStoreService event-store))))))

(fact
 :kube
 (-> (lk/injector)
     (mes-lk/module)
     (test-module)
     (lk/standard-descs)
     (lkt/kube-tests "mdb-es")) => "")
