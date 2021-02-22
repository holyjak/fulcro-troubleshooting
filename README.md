# fulcro-troubleshooting

A development-time library for [Fulcro](https://fulcro.fulcrologic.com/) that helps to detect problems earlier and find and fix their root cause faster.

For additional help, see the [Fulcro Troubleshooting Decision Tree](https://blog.jakubholy.net/2020/troubleshooting-fulcro/).

## Usage

Add the library to your project:

```clojure
;; deps.edn
:aliases
{:dev {:extra-deps {holyjak/fulcro-troubleshooting
                    {:git/url "https://github.com/holyjak/fulcro-troubleshooting"
                     :sha "463fb0dbb0a3cc73bbae538e190867afbd0867ac"}}}
```

When you create your Fulcro/RAD app, add the middleware provided by the library:

```clojure
(ns my.app
  (:require
    [com.fulcrologic.fulcro.application :as app]
    [holyjak.fulcro-troubleshooting :refer [troubleshooting-render-middleware]]))

(defonce app (app/fulcro-app {:render-middleware troubleshooting-render-middleware}))
```

## License

Copyleft 2021 Holyjak

Distributed under the Unlicense, see http://unlicense.org/.
