(ns mariadb-event-store.lk
  (:require [lambdakube.core :as lk]
            [lambdakube.util :as lku]
            [clojure.java.io :as io]
            [mariadb-event-store.core :refer []])
  (:import (mariadb_event_store MariaDBEventStoreService)))

(defn map-resources [res-list]
  (->> (for [res res-list]
         [res (-> res io/resource slurp)])
       (into {})))

(defn module [$]
  (-> $
      (lk/rule :axiom-event-store [:mariadb-event-store-config]
               (fn [{:keys [replication-factor num-shards password labels mdb-ver volume-spec db-res mysql-port]
                     :or {replication-factor 1
                          num-shards 1
                          password "thepassword"
                          labels {:app :mariadb-event-store}
                          mdb-ver "10.3"
                          volume-spec {:storageClassName :manual
                                       :accessModes ["ReadWriteOnce"]
                                       :resources {:requests {:storage "100Mi"}}}
                          db-res {}
                          mysql-port 3306}}]
                 (let [mdb-image (str "mariadb:" mdb-ver)]
                   (-> (lk/pod :mdb-es labels)
                       (lk/add-container :db mdb-image
                                         (-> {:resources db-res}
                                             (lk/add-env {:MYSQL_ROOT_PASSWORD password})))
                       (lk/add-container :install-schema mdb-image
                                         {:command ["sh" "/schema/install-schema.sh"]})
                       (lk/add-volume :sock-vol {:emptyDir {}}
                                      {:db "/var/run/mysqld/"
                                       :install-schema "/var/run/mysqld/"})
                       (lk/add-files-to-container :install-schema :schemafile "/schema"
                                                  (map-resources ["schema.sql" "install-schema.sh"]))
                       (lk/stateful-set (* replication-factor num-shards))
                       (lk/add-volume-claim-template :db-vol
                                                     volume-spec
                                                     {:db "/var/lib/mysql"})
                       (lk/add-annotation :host-pattern "mdb-es-%.mariadb-event-store")
                       (lk/add-annotation :port mysql-port)
                       (lk/add-annotation :user "root")
                       (lk/add-annotation :password password)
                       (lk/add-annotation :database "events")
                       (lk/add-annotation :num-shards num-shards)
                       (lk/add-annotation :num-replicas replication-factor)
                       (lku/add-itd-annotations MariaDBEventStoreService "https://github.com/brosenan/mariadb-event-store/raw/master/mariadb-event-store-uber.jar")
                       (lk/expose-headless :mariadb-event-store (lk/port :db :mysql mysql-port mysql-port))))))))


'(-> (lk/injector)
    (module)
    (lk/standard-descs)
    (lk/get-deployable {:mariadb-event-store-config {:replication-factor 3
                                                     :num-shards 2}})
    (lk/to-yaml)
    (println))

