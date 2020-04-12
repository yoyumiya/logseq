(ns frontend.components.home
  (:require [rum.core :as rum]
            [frontend.components.sidebar :as sidebar]))

(rum/defc home
  []
  (sidebar/sidebar (sidebar/main-content)))
