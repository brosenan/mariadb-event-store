(ns mariadb-event-store.core
  (:require [clojure.string :as str]))

(defn -init [props]
  (let [replication-factor (.get props "num-replicas")
        pattern (.get props "host-pattern")]
    {:port (.get props "port")
     :num-shards (.get props "num-shards")
     :num-replicas replication-factor
     :hostname (fn [s r]
                 (str/replace pattern "%" (str (+ (* s replication-factor) r))))}))
