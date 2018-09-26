(defproject brosenan/mariadb-event-store "0.1.0-SNAPSHOT"
  :description "An EventStoreService implementation based on an array of MariaDB instances"
  :url "https://github.com/brosenan/mariadb-event-store"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.9.0"]
                 [brosenan/event_store "0.0.4"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [hikari-cp "2.6.0"]
                 [org.mariadb.jdbc/mariadb-java-client "2.3.0"]
                 [com.taoensso/nippy "2.14.0"]
                 [pandect "0.6.1"]
                 [brosenan/lambdakube "0.8.4"]]
  :profiles {:dev {:dependencies [[midje "1.9.2"]]
                   :plugins [[lein-midje "3.2.1"]]}}
  :aot [mariadb-event-store.core mariadb-event-store.lk]
  :deploy-repositories [["releases" :clojars]])
