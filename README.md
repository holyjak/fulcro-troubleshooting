# fulcro-troubleshooting

A development-time library for [Fulcro](https://fulcro.fulcrologic.com/) that helps to detect problems earlier and find and fix their root cause faster.

For additional help, see the [Fulcro Troubleshooting Decision Tree](https://blog.jakubholy.net/2020/troubleshooting-fulcro/).

## Rationaly

Fulcro does an awesome job of checking your code and providing helpful messages but it is inherently limited by the fact that most of its checks are compile-time. `fulcro-troubleshooting` checks your application at run time and thus has much more insight into what is really going on. It also integrates with the UI so that you see the errors and warnings right in the UI, next to the place where you observe the problem.

## What can it do?

### Proper query inclusion

Warn you when a component's query is not included in its parent's:

![demo missing join](doc/demo-missing-join.jpg)

Experimental configuration (subject to change):

```clojure
(set! holyjak.fulcro-troubleshooting/*config*
      ;; return truthy to check the inclusing of the component's query in an ancestor
      {:query-inclusion-filter (fn [component-instance comp-class] 
                                 (not= comp-class :com.example.ui/MyComponent))})
``
### Valid idents

Warn when there is something fishy about the component's ident:

![demo bad ident - map](doc/demo-bad-ident-map.jpg)
![demo bad ident - nil](doc/demo-bad-ident-nil.jpg)

### Presence of child data

Warn when there is no data for a child, perhaps because the data has failed to load, or is at the wrong place of the DB, or because you have not provided `:initial-state` for the component (which is optional but crucial e.g. for Link Query - only components):

![demo missing join data](doc/demo-missing-join-data.jpg)

Experimental configuration (subject to change):

```clojure
(set! holyjak.fulcro-troubleshooting/*config*
      ;; return truthy for any join prop that should be check for having non-nil data in the props:
      {:join-prop-filter (fn [component-instance prop] (not= prop :jh/address))})
```

You can also get rid of this warning by using `:initial-state` and setting it to something non-nil such as `[]` for a list or `{}` for a map. (Though remember that in the Template Form `{}` means "include initial state from the child" so, if there is a child element for that prop, set also its initial state. And remember to propagate the initial state up all the way to the root component.)

### Valid :initial-state

Ideally, you would use the [template form](https://book.fulcrologic.com/#_template_mode) of `:initial-state` as it checks that you only include props that you query for.

This check controls that you actually return either nil or a map and that the map has no key
you do not query for (contrary to the template form check, this works also for the lambda form,
though it is less powerful).

### User components wrapped with [React Error Boundary](https://book.fulcrologic.com/#_react_errors)

Non-Fulcro components are wrapped with an Error Boundary so that if their render throws an exception, it is caught and displayed in the UI, instead of taking the whole page down.
## Status

Alpha quality but already pretty useful library, under active development. Do not hesitate to try it out and share your feedback!

Get in touch with `@holyjak` in the `#fulcro` channel of the Clojurians Slack if you have any questions, problems, ideas, or comments.

## Usage

Add the library to your project:

```clojure
;; deps.edn
:aliases
{:dev {:extra-deps {holyjak/fulcro-troubleshooting
                    {:git/url "https://github.com/holyjak/fulcro-troubleshooting"
                     ;; run `clojure -X:deps git-resolve-tags` to insert the correct :sha
                     :sha "177afe5a3c043777f944cb28b73332d6136796ae"
                     :tag "latest"}}}
```

and make sure that the `:dev` alias is activated and the library's names is automatically required:

```clojure
;; shadow-cljs.edn
{:deps {:aliases [:dev]}
 :builds {:main {:devtools {:preloads [holyjak.fulcro-troubleshooting ...] ...}
                 ...}}}
```

(Assuming you have activated the `dev` alias in your `shadow-cljs.edn`.)

When you create your Fulcro/RAD app, add the middleware provided by the library:

```clojure
(ns my.app
  (:require
    ;; [holyjak.fulcro-troubleshooting] ; add if you haven't added it as :preload
    [com.fulcrologic.fulcro.application :as app]))

(defonce app (app/fulcro-app {:render-middleware 
                              (when goog.DEBUG js/holyjak.fulcro_troubleshooting.troubleshooting_render_middleware)}))
;; we use js/.. instead of holyjak.fulcro-troubleshooting/troubleshooting-render-middleware so that
;; the code will still compile for prod release, when the lib is not included
```

## TODO

- check initial state (if present) to be a map with keys <= query keys
- add Error Boundary
- add tests

## License

Copyleft 2021 Holyjak

Distributed under the Unlicense, see http://unlicense.org/.
