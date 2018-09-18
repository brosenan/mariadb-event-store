(defproject brosenan/mariadb-event-store "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.8.0"]
                 [brosenan/event_store "0.0.1-SNAPSHOT"]
                 [org.clojure/java.jdbc "0.7.8"]
                 [org.mariadb.jdbc/mariadb-java-client "2.3.0"]
                 [com.taoensso/nippy "2.14.0"]]
  :profiles {:dev {:dependencies [[midje "1.9.2"]]
                   :plugins [[lein-midje "3.2.1"]]}}
  :aot :all
  :deploy-repositories [["releases" :clojars]])
