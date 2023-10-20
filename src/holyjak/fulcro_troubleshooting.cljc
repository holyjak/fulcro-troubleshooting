(ns holyjak.fulcro-troubleshooting
  (:require
   [clojure.set]
   [clojure.string :as str]
   [com.fulcrologic.fulcro.components :as comp] 
   [com.fulcrologic.fulcro.dom :as dom]
   [com.fulcrologic.fulcro.react.error-boundaries :as fulcro.eb :refer [error-boundary]]
   [edn-query-language.core :as eql]))

(def ns--multiple-roots-renderer "com.fulcrologic.fulcro.rendering.multiple-roots-renderer")
(def ns--error-boundaries "com.fulcrologic.fulcro.react.error-boundaries")

(defn component-ns 
  "Return the namespace of the given component, as a string"
  [component-instance]
  (some->
   (comp/get-class component-instance)
   comp/class->registry-key
   namespace))

(defn get-parent [component-instance]
  (or (comp/get-parent component-instance)
      ;; com.fulcrologic.fulcro.react.error-boundaries/ErrorBoundary does not have parent as normal components do
      ;; but instead has the prop `:parent`
      (some-> component-instance comp/props :parent)))

(set! fulcro.eb/*render-error*
      (fn [component-instance cause]
        (str "There was an error rendering " 
             (if (= ns--error-boundaries (component-ns component-instance)) ; always true?!
               (some-> component-instance get-parent comp/component-name)
               (comp/component-name component-instance))
             ": " (ex-message cause))))

(def builtin-join-check-excludes 
  "Do not check components of these classes for having their query included in their parent"
  #{; The AutocompleteFieldRoot asks for all the data instead of including the field's query
    :com.fulcrologic.rad.rendering.semantic-ui.autocomplete/AutocompleteField})

(defn instance-of? 
  "Is the given component instance of the given class, presented as a keyword?
   
   Ex.: (instance-of? c :com.fulcrologic.rad.rendering.semantic-ui.autocomplete/AutocompleteField)"
  [component-instance class-kwd]
  (some->
   (comp/get-class component-instance)
   comp/class->registry-key
   (= class-kwd)))

(def ^:dynamic *config* 
  "EXPERIMENTAL - subject to change and removal
   
   Provide custom configuration for the checks. Supported options:
   
   - `:initial-state-filter` - `(fn [component-instance property-name])` that should return
     truthy for any initial-state prop that should be check for being also queried for.
   - `:join-prop-filter` - `(fn [component-instance property-name])` that should return
     truthy for any join prop that should be check for having non-nil data in the props.
   - `:allow-nil-ident` - do not warn about missing idents in non-root components with a query
  - `:error-boundaries?` - set to `false` to disable wrapping components in React Error Boundary"
  {})

(defn- ensure! [v pred msg]
  (assert (pred v) msg)
  v)

(defn- get-config-filter [config-prop-name component-instance]
  (if-let [user-filter (get *config* config-prop-name)]
    (partial 
      (ensure! user-filter ifn? (str "The value of the config option " config-prop-name " must be a function"))
      component-instance)
    (constantly true)))

(defn- add-error-boundaries? [] (:error-boundaries? *config* true))

(defn skip-join-check? [component-instance]
  (let [user-filter (get-config-filter :query-inclusion-filter component-instance)]
    (or (some (partial instance-of? component-instance)
              builtin-join-check-excludes)
        (not (user-filter (some->
                           (comp/get-class component-instance)
                           comp/class->registry-key))))))

(defn now-ms []
  (inst-ms
   #?(:clj  (java.util.Date.)
      :cljs (js/Date.))))

(defn ident [component-instance]
  (comp/ident component-instance (comp/props component-instance)))

(defn floating-root-component? [component-instance]
  (some-> (get-parent component-instance)
          (component-ns)
          (= ns--multiple-roots-renderer)))

(defn root-component? [component-instance]
  (or (nil? (get-parent component-instance))
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
  (boolean (some-> component-instance 
                   comp/component-options 
                   :com.fulcrologic.rad.form/field-options 
                   prop 
                   :com.fulcrologic.rad.picker-options/query-key)))

;; ----

(defn closest-ancestor-with-query
  "Return the closest ancestor that has a query (or the Root)"
  [component-instance]
  {:pre [(comp/component-instance? component-instance)]}
  (let [ancestors (->> (rest (iterate #(some-> % get-parent) component-instance))
                       (take-while some?))]
    (or
     (->> ancestors
          (sequence (comp
                     (filter comp/query)
                     (take 1)))
          first)
     (last ancestors))))

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
  ;; FIXME Also error if no ancestor found (e.g. if the Root does not have a query)!
  (when-let [ancestor (and (not (skip-join-check? component-instance)) 
                           (ancestor-failing-to-join-query-of component-instance))]
    (ex-info
     (str "*Proper query inclusion*: The query of " (comp/component-name component-instance)
          " should be joined into the query of its closest stateful ancestor " (comp/component-name ancestor)
          " (" (ident-str ancestor)
          "). Something like: "
          "`{:some-prop (comp/get-query " (comp/component-name component-instance) ")}`.")
     {::id       :disconnected-query
      :component (comp/get-class component-instance)
      :ancestor  (comp/get-class ancestor)})))

(defn non-current-router-target-join? [[ident-id _] prop]
  (and (= ident-id :com.fulcrologic.fulcro.routing.dynamic-routing/id)
       (str/starts-with? (name prop) "alt")))

(defn- link-or-ident-query? [query-prop] 
  (and (vector? query-prop) (= 2 (count query-prop))))

(defn- link-query? [query-prop]
  (and (link-or-ident-query? query-prop) (= '_ (second query-prop))))

(defn empty-props-warning [ident props]
  (let [link-or-ident-query? (link-or-ident-query? (first props))]
    (str "*Presence of child data*: These "
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
        user-filter (get-config-filter :join-prop-filter component-instance)

        relevant-join-props
        (->> (comp/query component-instance)
             (filter query-elm->component)
             (map ffirst)
             (remove (partial non-current-router-target-join? ident))
             (remove (partial rad-picker-attribute? component-instance))
             (map #(cond-> %
                     ;; link query such as [:root-prop '_] will get data under `:root-prop`
                     (link-query? %) (first)))
             (filter user-filter)
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
        msg-ident-should (str "*Valid idents*: The ident `" (pr-str ident) "` should ")]
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

;; (defn check-root-query-valid [component-instance]
;;   (when-let [root-query (and (root-component? component-instance)
;;                              (comp/get-query component-instance))]
;;     (try
;;       (eql/query->ast root-query)
;;       (catch #?(:cljs :default :clj Throwable) e
;;         (ex-info "Some part of the combined query is not a valid EQL" {:query root-query} e)))))

(defn- query->props
  "Extract top-level prop names from a query, whether they are in a join or not."
  [query]
  ;; Note: Could contain :a, {:a [..}]}, {[:a '_] [..]} ...
  (->> (eql/query->ast query)
       :children
       (map :dispatch-key)))

(defn check-initial-state [component-instance]
  (when-let [st (and (comp/query component-instance)
                     (comp/get-initial-state component-instance))]
    (cond
      (not (map? st))
      (ex-info (str "*Valid :initial-state*: Initial state must be nil or a map, is `" (pr-str st) ; type does not work for [] here
                    "` (it is the props your component gets passed on its 1st render)")
               {:initial-state st})
      
      (seq (clojure.set/difference
            (set (->> (keys st) (filter (get-config-filter :initial-state-filter component-instance))))
            (set (->> (query->props (comp/query component-instance))
                      (map #(cond-> %
                              (link-or-ident-query? %) (first)))))))
      (ex-info (str "*Valid :initial-state*: Initial state should only contain keys"
                    " for the props the component queries for. Has these: "
                    (str/join ", " (keys st)) ".")
               {:initial-state-keys (keys st)
                :query (comp/query component-instance)})
      
      :else
      nil)))

(defn check-query-for-duplicates [component-instance]
  (when-let [q-props (some->> (comp/query component-instance)
                        query->props
                        sort)]
    (when (> (count q-props) (count (set q-props)))
      (let [dups (->> (map (fn [p1 p2] (#{p1} p2)) q-props (next q-props)) 
                      (remove nil?)
                      dedupe)]
       (ex-info (str "*No duplicates in a query* Each prop must only appear once in a query but these are duplicated: "
                     (str/join ", " dups) ".")
                {:query-props q-props
                 :duplicates dups
                 :query (comp/query component-instance)})))))

;; ----------------- running + caching checks

(defn log [& args]
  #?(:cljs (apply js/console.debug "fulcro-troubleshooting:" args)))

(def cached-errors (atom nil))

(defn debounce ; TODO goog.functions has `debounce`, should we use that?
  "If `f` has been run 'recently' then return the cached errors otherwise run and cache it.
   
   This is because `render` can be called multiple times in a row and we don't want to re-do the
   possibly expensive checks so we set a reasonably small 'cache time' for the errors."
  [component-instance checks-fn]
  (let [[ts errors] (get @cached-errors component-instance)]
    (if (and errors (>= ts (- (now-ms) 10)))
      errors
      (if-let [new-errors (checks-fn)]
        (do (swap! cached-errors assoc component-instance [(now-ms) new-errors]) 
            new-errors)
        (do (when errors (swap! cached-errors dissoc component-instance)) 
            nil)))))

(defn run-checks [component-instance]
  (debounce
   component-instance
   #(do (log "Checking" (comp/component-name component-instance))
        (->> ((juxt
               check-ident
               check-missing-child-prop
               check-query-inclusion
               check-initial-state
               check-query-for-duplicates)
              component-instance)
             (remove nil?)
             seq))))

;; ----------------- public functions

(defn is-error-boundary? [component-instance]
  (= (component-ns component-instance)
     ns--error-boundaries))

(defn ignore? [component-instance]
  (or
    (is-error-boundary? component-instance)
    (-> (component-ns component-instance)
               ;; For efficiency, do not wrap Fulcro's own components (...fulcro.*, ...rad.*)
               ;; though not sure whether / how much it matters               
        (str/starts-with? "com.fulcrologic."))))

(defn maybe-wrap-with-errors [component-instance real-render]
  (if-let [errors (run-checks component-instance)]
    (dom/div :.fulcro-troubleshooting-error ; FIXME Cannot place this eg inside table/tr ... - could get mount root and prepend a child to it?
      {:style {:border "orange 2px solid"}}
      (dom/div
        (dom/p "WARNING(s) for " (comp/component-name component-instance) " (" (ident-str component-instance) "):")
        (map-indexed #(dom/p {:key %1} (ex-message %2)) errors))
      (real-render))
    (real-render)))

(defn ^:export troubleshooting-render-middleware
  "Add this middleware to your fulcro app (as `(app/fulcro-app {:render-middleware ...})`)
   to get notified in the UI when you did something wrong."
  [component-instance real-render]
  (cond
    (ignore? component-instance)
    (real-render)

    (not (add-error-boundaries?))
    (maybe-wrap-with-errors component-instance real-render)

    :else
    (error-boundary ; FIXME: Breaks F.Inspect's Element picker, see #6
      (maybe-wrap-with-errors component-instance real-render))))
