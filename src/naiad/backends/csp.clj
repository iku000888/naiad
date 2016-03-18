(ns naiad.backends.csp
  (:require [clojure.core.async :as async :refer [close! <! >! go]]
            [naiad.graph :refer [links]]))


(defmulti construct! :type)

(defn xf-pipe [to xf from]
  (go (let [a (volatile! [])
            rf (xf (fn [_ v]
                     (vswap! a conj v)))]
        (loop []
          (if-some [v (<! from)]
            (let [_ (rf nil v)
                  exit? (loop [[h & t] @a]
                          (when h
                            (if (>! to h)
                              (recur t)
                              :exit)))]
              (if exit?
                (close! to)
                (do (vreset! a [])
                    (recur))))

            (let [_ (xf nil)]
              (doseq [v @a]
                (>! to v))
              (close! to)))))))

(defmethod construct! :default
  [{:keys [transducer? inputs outputs f] :as node}]
  (assert (and transducer?
            (= (count inputs) 1)
            (= (count outputs) 1))
    (str "Cannot construct node: " node))
  (xf-pipe (:out outputs) f (:in inputs)))

(defn inject-channels [mappings mp]
  (reduce-kv
    (fn [acc k v]
      (let [c (mappings v)]
        (assert c (str "Mapping not found for " v))
        (assoc acc k c)))
    mp
    mp))

(defn construct-channel [{:keys [existing-channel]}]
  (cond
    existing-channel existing-channel
    :else (async/chan)))



(defn construct-graph [graph]
  (let [link-ids (links graph)
        chans (zipmap link-ids
                (->> link-ids
                  ;; Get any options for the links
                  (map graph)
                  (map construct-channel)))]
    (reduce-kv
      (fn [acc k {:keys [type] :as node}]
        (if (= type :naiad/link-annotation)
          (assoc acc k node)
          (let [{:keys [inputs outputs closes]} node
                new-node (assoc node
                           :inputs (inject-channels chans inputs)
                           :outputs (inject-channels chans outputs)
                           :closes (inject-channels chans closes))]
            (construct! new-node)
            (assoc acc k new-node))))
      graph
      graph)))
