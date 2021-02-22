# fulcro-troubleshooting

A development-time library for [Fulcro](https://fulcro.fulcrologic.com/) that helps to detect problems earlier and find and fix their root cause faster.

For additional help, see the [Fulcro Troubleshooting Decision Tree](https://blog.jakubholy.net/2020/troubleshooting-fulcro/).

## Usage

When you create your Fulcro/RAD app, add the middleware provided by the app:

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
