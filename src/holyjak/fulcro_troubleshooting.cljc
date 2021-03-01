(ns holyjak.fulcro-troubleshooting
  (:require
   [clojure.set]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp] 
   [com.fulcrologic.fulcro.dom :as dom]))

(def builtin-join-check-excludes 
  "Do not check components of these classes for having their query included in their parent"
  #{; The AutocompleteFieldRoot asks for all the data instead of including the field's query
    :com.fulcrologic.rad.rendering.semantic-ui.autocomplete/AutocompleteField})

(defn instance-of? 
  "Is the given component instance of the given class, presented as a keyword?"
  [component-instance class-kwd]
  (some->
   (comp/get-class component-instance)
   comp/class->registry-key
   (= class-kwd)))

(defn skip-join-check? [component-instance]
  (some (partial instance-of? component-instance)
        builtin-join-check-excludes))

(def ^:dynamic *config* 
  "EXPERIMENTAL - subject to change and removal
   
   Provide custom configuration for the checks. Supported options:
   
   - `:join-prop-filter` - `(fn [component-instance property-name])` that should return
     truthy for any join prop that should be check for having non-nil data in the props.
   - `:allow-nil-ident` - do not warn about missing idents in non-root components with a query"
  {})

(defn now-ms []
  (inst-ms
   #?(:clj  (Date.)
      :cljs (js/Date.))))

(defn ident [component-instance]
  (comp/ident component-instance (comp/props component-instance)))

(defn floating-root-component? [component-instance]
  (-> (comp/get-parent component-instance)
      (comp/component-name)
      (str/starts-with? "com.fulcrologic.fulcro.rendering.multiple-roots-renderer/")))

(defn root-component? [component-instance]
  (or (nil? (comp/get-parent component-instance))
      (floating-root-component? component-instance)))

(defn ui-only-component? 
  "A UI-only component does not have any query and does not need ident"
  [component-instance]
  (nil? (comp/query component-instance)))


(def ident-str (comp pr-str ident))

;; ---

(defn rad-picker-attribute? 
  "If the given prop is backed by a RAD Picker then it is likely not a problem if the client DB
   lacks data for it because they are loaded on demand."
  [component-instance prop]
  ;; The user is likely to see this warning from the rad.picker-options in the console:
  ;; (log/warn "No options cache found in props for" (comp/component-name cls) ". This could mean options have not been "
  ;;        "loaded, or that you forgot to put `[::picker-options/options-cache '_]` on your query. NOTE: This warning can be "
  ;;        "a false alarm if the application just started and no picker options have yet been loaded.")
  (boolean (-> component-instance 
               comp/component-options 
               :com.fulcrologic.rad.form/field-options 
               prop 
               :com.fulcrologic.rad.picker-options/query-key)))

;; ----

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

(defn query-elm->component [query-element]
  (when (map? query-element)
    (-> query-element first val meta :component)))

(defn joined-in-query? [component-instance query]
  (if-let [component (comp/get-class component-instance)]
    (some #(and (map? %) ; is it a join? -> get its value, which should be (comp/get-query X)
                (= component (query-elm->component %)))
          query)
    true))

(defn ancestor-failing-to-join-query-of
  "Returns the ancestor component (typically the parent) that is expected to join
  the query of the given component into its own but does not
  or nil if either joined correctly or has no query to join."
  [component-instance]
  (let [ancestor       (when (and (not (ui-only-component? component-instance))
                                  (not (root-component? component-instance)))
                         (closest-ancestor-with-query component-instance))
        anc-query      (some-> ancestor comp/query)]
    (when-not (some->> anc-query (joined-in-query? component-instance))
      ancestor)))

;; ----------------- checks
;; 
(defn check-query-inclusion
  "Is this component's query (if any) included in the parent (or another closest ancestor with a query)?"
  [component-instance]
  (when-let [ancestor (and (not (skip-join-check? component-instance)) 
                           (ancestor-failing-to-join-query-of component-instance))]
    (ex-info
     (str "The query of " (comp/component-name component-instance)
          " should be joined into the one of its stateful ancestor " (comp/component-name ancestor)
          " (" (ident-str ancestor)
          "). Something like: "
          "`{:some-prop (comp/get-query " (comp/component-name component-instance) ")}`.")
     {::id       :disconnected-query
      :component (comp/get-class component-instance)
      :ancestor  (comp/get-class ancestor)})))

(defn non-current-router-target-join? [[ident-id _] prop]
  (and (= ident-id :com.fulcrologic.fulcro.routing.dynamic-routing/id)
       (str/starts-with? (name prop) "alt")))

(defn empty-props-warning [ident props]
  (let [link-or-ident-query? (vector? (first props))]
    (str "These "
         (when link-or-ident-query? "ident/link query")
         " join props " (str/join ", " props)
         " have no data in the client DB (expected them " 
         (if (or link-or-ident-query? (nil? ident)) 
           (str "at the root")
           (str "under `" (str/join " " ident) "`"))
         "). Thus they will get `nil`. This might be intended - or you might have"
         " forgotten to provide `:initial-state {}` for a component with only Link Query"
         " (and/or forgot to include it in the parent's initial state)"
         " or you might have `load!`-ed data into the wrong part of the client DB (look at data targeting).")))

(defn check-missing-child-prop
  "Is the join prop for a child missing from the client DB (=> the child will get nil props)?
  This could be due to a Link Query - only child with missing initial state or data that was
  loaded into the wrong place in the client DB."
  [component-instance]
  (let [props (comp/props component-instance)
        ident (comp/ident component-instance (comp/props component-instance))
        user-filter (or (:join-prop-filter *config*) (constantly true))
        
        relevant-join-props
        (->> (comp/query component-instance)
             (filter query-elm->component)
             (map ffirst)
             (remove (partial non-current-router-target-join? ident))
             (remove (partial rad-picker-attribute? component-instance))
             (filter (partial user-filter component-instance))
             set)
        
        joins-w-data
        (->> (select-keys props relevant-join-props)
             (filter (fn [[_ val]] (some? val)))
             (map first)
             set)]
    
    ;; (when (= "com.example.ui/MainRouter" (comp/component-name component-instance))
    ;;   (def *dbg component-instance)
    ;;   #_(js/console.log "check-missing-child-prop for Root:" {:join-props join-props, :joins-w-data joins-w-data}))
    
    (when-let [{empty-join-props true, empty-root-join-props false} 
               (not-empty (group-by keyword? (clojure.set/difference relevant-join-props joins-w-data)))]
      (ex-info (str (some->> empty-join-props (empty-props-warning (comp/ident component-instance props)))
                    (some->> empty-root-join-props (empty-props-warning (comp/ident component-instance props))) ) 
               {:level :warn
                :join-props-without-data empty-join-props
                :root-join-props-without-data empty-root-join-props
                ::id :empty-join-props}))))

(defn check-ident 
  "Does the ident have the correct form?"
  [component-instance]
  (let [ident (comp/ident component-instance (comp/props component-instance))
        d      {:ident ident, ::id :bad-ident}
        msg-ident-should (str "The ident `" (pr-str ident) "` should ")]
    (cond
      (root-component? component-instance)
      (when-not (nil? ident)
        (ex-info (str msg-ident-should "be nil because a root components should have no ident") d))

      (and (nil? ident)
           (or (ui-only-component? component-instance)
               (:allow-nil-ident *config*)))
      nil

      (not (vector? ident))
      (ex-info (str msg-ident-should
                    "be a vector. Examples: `[:component/id :MyList]`, `[:person/id 123]`")
               d)

      (not (= 2 (count ident)))
      (ex-info (str msg-ident-should "have length 2") d)

      (and (nil? (second ident))
           (seq (comp/props component-instance)))
      (ex-info (str msg-ident-should "likely not have nil as the second element. Have you used Template Ident"
                    " instead of a lambda such as `(fn [] [:component/id :MyCompo])`? Or is the entity data"
                    " missing the id property?! (Which should never happen; are your resolvers wrong?)")
               d)

      :else nil)))


;; ----------------- running + caching checks

(def cached-errors (atom nil))

(defn debounce 
  "If `f` has been run 'recently' then return the cached errors otherwise run and cache it.
   
   This is because `render` can be called multiple times in a row and we don't want to re-do the
   possibly expensive checks so we set a reasonably small 'cache time' for the errors."
  [component-instance checks-fn]
  (let [[ts errors] (get @cached-errors component-instance)]
    (if (and errors (>= ts (- (now-ms) 10)))
      errors
      (if-let [errors (checks-fn)]
        (do (swap! cached-errors assoc component-instance [(now-ms) errors]) 
            errors)
        (do (swap! cached-errors dissoc component-instance) 
            nil)))))

(defn run-checks [component-instance]
  (debounce
   component-instance
   #(->> ((juxt
           check-ident
           check-missing-child-prop
           check-query-inclusion)
          component-instance)
         (remove nil?)
         seq)))

;; ----------------- public functions

(defn troubleshooting-render-middleware 
  "Add this middleware to your fulcro app (as `(app/fulcro-app {:render-middleware ...})`)
   to get notified in the UI when you did something wrong."
  [component-instance real-render]
  (if-let [errors (run-checks component-instance)]
    (dom/div :.fulcro-troubleshooting-error ; FIXME Cannot place this eg inside table/tr ...
             {:style {:border "lime 2px solid"}}
             (dom/div
              (dom/p "WARNING(s) for " (comp/component-name component-instance) " (" (ident-str component-instance) "):")
              (map-indexed #(dom/p {:key %1} (ex-message %2)) errors))
             (real-render))
    (real-render)))
