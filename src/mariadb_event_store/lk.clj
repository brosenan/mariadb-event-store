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
                       (update :spec assoc :podManagementPolicy :Parallel)
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
                       (lk/expose-headless :mariadb-event-store (lk/port :db :mysql mysql-port mysql-port))
                       ;; An deployment to detect when all database
                       ;; nodes are active, and then open a socket to
                       ;; allow other pods to wait for it.
                       (update :$additional conj
                               (-> (lk/pod :wait-for-all-dbs {:belongs-to :mdb-es})
                                   (lk/add-init-container :wait-for-dbs "busybox"
                                                          (-> {:command ["sh" "-c" "while ! sh -e /etc/wait-for-db/scan-dbs.sh; do sleep 1; done"]}
                                                              (lk/add-env {:NUM_NODES (str (dec (* replication-factor num-shards)))
                                                                           :DB_PORT (str mysql-port)})))
                                   (lk/add-files-to-container :wait-for-dbs :scan-dbs "/etc/wait-for-db"
                                                              {"scan-dbs.sh" "for i in $(seq 0 $NUM_NODES); do nc -z mdb-es-$i.mariadb-event-store $DB_PORT; done"})
                                   (lk/add-container :respond "busybox"
                                                     {:command ["sh" "-c" "while true; do nc -lp 8888; done"]})
                                   (lk/deployment 1)
                                   (lk/expose-cluster-ip :mdb-es-is-ready (lk/port :respond :db 8888 8888))))))))))


'(-> (lk/injector)
    (module)
    (lk/standard-descs)
    (lk/get-deployable {:mariadb-event-store-config {:replication-factor 3
                                                     :num-shards 2}})
    (lk/to-yaml)
    (println))

