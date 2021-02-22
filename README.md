# fulcro-troubleshooting

A development-time library for [Fulcro](https://fulcro.fulcrologic.com/) that helps to detect problems earlier and find and fix their root cause faster.

For additional help, see the [Fulcro Troubleshooting Decision Tree](https://blog.jakubholy.net/2020/troubleshooting-fulcro/).

## What can it do?

Warn you when a component's query is not included in its parent's:

![demo missing join](doc/demo-missing-join.jpg)

More to come - see the roadmap below.

## Status

This is very alpha, under active development. However it is already useful. So do not hesitate to try it out!

Get in touch with `@holyjak` in the `#fulcro` channel of the Clojurians Slack if you have any questions or comments.

## Usage

Add the library to your project:

```clojure
;; deps.edn
:aliases
{:dev {:extra-deps {holyjak/fulcro-troubleshooting
                    {:git/url "https://github.com/holyjak/fulcro-troubleshooting"
                     ;; run `clojure -X:deps git-resolve-tags` to insert the correct :sha
                     :tag "latest"}}}
```

(Assuming you have activated the `dev` alias in your `shadow-cljs.edn`.)

When you create your Fulcro/RAD app, add the middleware provided by the library:

```clojure
(ns my.app
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [holyjak.fulcro-troubleshooting :refer [troubleshooting-render-middleware]]))

(defonce app (app/fulcro-app {:render-middleware troubleshooting-render-middleware}))
```

## Roadmap

- [x] Warn when a component's query is not included in its parent's
- [ ] Warn when data for a component is missing in the client DB due to bad ident or missing load targeting

## License

Copyleft 2021 Holyjak

Distributed under the Unlicense, see http://unlicense.org/.
