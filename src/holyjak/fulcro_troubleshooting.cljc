(ns holyjak.fulcro-troubleshooting
  (:require
   [com.fulcrologic.fulcro.components :as comp] 
   [com.fulcrologic.fulcro.dom :as dom]
   ))

(defn now-ms []
  (inst-ms
   #?(:clj  (Date.)
      :cljs (js/Date.))))

(def unjoined-components (atom nil))

(defn closest-ancestor-with-query
  "Return the closest ancestor that has a query (or nil)"
  [component-instance]
  {:pre [(comp/component-instance? component-instance)]}
  (->> (iterate #(some-> % comp/get-parent) component-instance)
       (sequence (comp
                   (drop 1)
                   (filter comp/query)
                   (take 1)))
       first))

(defn joined-in-query? [component-instance query]
  (if-let [component (comp/get-class component-instance)]
    (some #(and (map? %) ; is it a join? -> get its value, which should be (comp/get-query X)
                (= component (-> % first val meta :component)))
          query)
    true))

(defn ancestor-failing-to-join-query-of*
  "Returns the ancestor component (typically the parent) that is expected to join
  the query of the given component into its own but does not
  or nil if either joined correctly or has no query to join."
  [component-instance]
  (let [ancestor       (and (comp/query component-instance)
                            (comp/get-parent component-instance) ; it is not the root
                            (closest-ancestor-with-query component-instance))
        anc-query      (and ancestor (comp/query ancestor))]
    (when-not (some->> anc-query (joined-in-query? component-instance))
      ancestor)))

(defn cached-failing-ancestor [component-instance]
  (when-let [[ts ancestor] (get @unjoined-components component-instance)]
    (if (>= (+ ts 10)
            (now-ms))
      ancestor
      (do (swap! unjoined-components dissoc component-instance)
          nil))))


(defn ancestor-failing-to-join-query-of [component-instance]
  (or (cached-failing-ancestor component-instance)
      (doto (ancestor-failing-to-join-query-of* component-instance)
        (some->> (vector (now-ms))
                 (swap! unjoined-components assoc component-instance)))))

(defn troubleshooting-render-middleware [component-instance real-render]
  (if-let [ancestor (ancestor-failing-to-join-query-of component-instance)]
    (dom/div {:style {:border "lime 2px solid"}}
             (dom/div
               (str "WARNING: The query of " (comp/component-name component-instance)
                    " should be joined into the one of its stateful ancestor " (comp/component-name ancestor)
                    ". Something like: ")
               (dom/code nil (str "{:some-prop (comp/get-query " (comp/component-name component-instance) ")}")))
             (real-render))
    (real-render)))
