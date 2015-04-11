(ns satellite.relay)

;; type state = Map (Any, state) | List state

(defn state-exception
  [x]
  (ex-info "not type state" {:arg x}))

(defn get-in-state
  "Like get-in, but for state!

  Args:
      x: collection, type state
      ks: sequence of keys

  Output:
      sequence"
  [x [k & ks :as kks]]
  (let [helper
        (fn [x [k & ks :as kks]]
          (cond
           (nil? k) x
           (map? x) (if (seq ks)
                      (get-in-state (get x k) ks)
                      (get x k))
           (sequential? x) (map #(get-in-state % kks) x)
           :else (throw (state-exception x))))]
    (flatten (helper x kks))))
