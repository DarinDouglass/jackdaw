(ns user
  (:require [clojure.string :as str]
            [jackdaw.admin :as ja]
            [jackdaw.streams :as j]
            [jackdaw.repl :as repl]
            [integrant.core :as ig]
            [topology-grapher.generator :refer [describe-topology]]
            [integrant.repl :refer [clear go halt prep init reset reset-all]]
            [word-count]))


(defn topology->topic-metadata
  "Takes a topology and streams config and walks the topology to find
  all the user-defined topics."
  [topology streams-config]
  (->> (describe-topology (.build (j/streams-builder* topology))
                          streams-config)
       (map :nodes)
       (reduce concat)
       (filter #(= :topic (:type %)))
       (remove (fn [x] (re-matches #".*STATE-STORE.*" (:name x))))
       (map :name)
       (reduce (fn [acc x]
                 (assoc acc (keyword x) (get repl/topic-metadata x)))
               {})))

(def repl-config
  "The development config.
  When the 'dev' alias is active, this config will be used."
  {:topology {:topology-builder word-count/topology-builder}

   :topics {:streams-config word-count/streams-config
            :client-config (select-keys word-count/streams-config
                                        ["bootstrap.servers"])
            :topology (ig/ref :topology)}

   :app {:streams-config word-count/streams-config
         :topology (ig/ref :topology)
         :topics (ig/ref :topics)}})


(defmethod ig/init-key :topology [_ {:keys [topology-builder]}]
  (let [streams-builder (j/streams-builder)]
    ((topology-builder repl/topic-metadata) streams-builder)))

(defmethod ig/init-key :topics [_ {:keys [streams-config client-config topology]
                                   :as opts}]
  (let [topic-metadata (topology->topic-metadata topology streams-config)]
    (with-open [client (ja/->AdminClient client-config)]
      (ja/create-topics! client (vals topic-metadata)))
    (assoc opts :topic-metadata topic-metadata)))

(defmethod ig/init-key :app [_ {:keys [streams-config topology] :as opts}]
  (let [streams-app (j/kafka-streams topology streams-config)]
    (j/start streams-app)
    (assoc opts :streams-app streams-app)))

(defmethod ig/halt-key! :topics [_ {:keys [client-config topic-metadata]}]
  (let [re (re-pattern (str "(" (->> topic-metadata
                                     keys
                                     (map name)
                                     (str/join "|"))
                            ")"))]
    (repl/re-delete-topics client-config re)))

(defmethod ig/halt-key! :app [_ {:keys [streams-config topics streams-app]}]
  (j/close streams-app)
  ;; BUG: Does not delete state on reset!
  (repl/destroy-state-stores streams-config)
  (let [re (re-pattern (str "(" (get streams-config "application.id") ")"))]
    (repl/re-delete-topics (:client-config topics) re)))


(integrant.repl/set-prep! (constantly repl-config))
