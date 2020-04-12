(ns frontend.handler
  (:refer-clojure :exclude [clone load-file])
  (:require [frontend.git :as git]
            [frontend.fs :as fs]
            [frontend.state :as state]
            [frontend.db :as db]
            [frontend.storage :as storage]
            [frontend.util :as util]
            [frontend.config :as config]
            [clojure.walk :as walk]
            [clojure.string :as string]
            [promesa.core :as p]
            [cljs-bean.core :as bean]
            [reitit.frontend.easy :as rfe]
            [goog.crypt.base64 :as b64]
            [goog.object :as gobj]
            [goog.dom :as gdom]
            [rum.core :as rum]
            [datascript.core :as d]
            [frontend.utf8 :as utf8]
            [frontend.image :as image]
            [clojure.set :as set]
            [cljs-bean.core :as bean])
  (:import [goog.events EventHandler]))

;; TODO: replace all util/p-handle with p/let

(defn set-state-kv!
  [key value]
  (swap! state/state assoc key value))

(defn get-github-token
  []
  (get-in @state/state [:me :access-token]))

(defn load-file
  [repo-url path]
  (fs/read-file (git/get-repo-dir repo-url) path))

(defn- hidden?
  [path patterns]
  (some (fn [pattern]
          (or
           (= path pattern)
           (and (string/starts-with? pattern "/")
                (= (str "/" (first (string/split path #"/")))
                   pattern)))) patterns))

(defn- get-format
  [file]
  (string/lower-case (last (string/split file #"\."))))

(def text-formats
  #{"org" "md" "markdown" "adoc" "asciidoc" "rst" "dat" "txt" "json" "yml" "xml"
    ;; maybe should support coding
    })

(def img-formats
  #{"png" "jpg" "jpeg" "gif" "svg" "bmp" "ico"})

(def all-formats
  (set/union text-formats img-formats))

(defn- keep-formats
  [files formats]
  (filter
   (fn [file]
     (let [format (get-format file)]
       (contains? formats format)))
   files))

(defn- only-text-formats
  [files]
  (keep-formats files text-formats))

;; TODO: no atom version
(defn load-files
  [repo-url]
  (set-state-kv! :repo/cloning? false)
  (set-state-kv! :repo/loading-files? true)
  (let [files-atom (atom nil)]
    (-> (p/let [files (bean/->clj (git/list-files repo-url))
                patterns-content (load-file repo-url config/hidden-file)]
          (reset! files-atom files)
          (when patterns-content
            (let [patterns (string/split patterns-content #"\n")]
              (reset! files-atom (remove (fn [path] (hidden? path patterns)) files)))))
        (p/finally
          (fn []
            @files-atom)))))

(defn- set-latest-commit!
  [hash]
  (set-state-kv! :git/latest-commit hash)
  (storage/set :git/latest-commit hash))

(defn- set-git-status!
  [value]
  (set-state-kv! :git/status value)
  (storage/set :git/status value))

(defn- set-git-error!
  [value]
  (set-state-kv! :git/error value)
  (storage/set :git/error (pr-str value)))

(defn set-latest-journals!
  []
  (set-state-kv! :latest-journals (db/get-latest-journals {})))

(defn git-add-commit
  [repo-url file message content]
  (set-git-status! :commit)
  (db/reset-file! repo-url file content)
  (git/add-commit repo-url file message
                  (fn []
                    (set-git-status! :should-push))
                  (fn [error]
                    (prn "Commit failed, "
                         {:repo repo-url
                          :file file
                          :message message})
                    (set-git-status! :commit-failed)
                    (set-git-error! error))))

;; journals

;; org-journal format, something like `* Tuesday, 06/04/13`
(defn default-month-journal-content
  []
  (let [{:keys [year month day]} (util/get-date)
        last-day (util/get-month-last-day)
        month-pad (if (< month 10) (str "0" month) month)]
    (->> (map
           (fn [day]
             (let [day-pad (if (< day 10) (str "0" day) day)
                   weekday (util/get-weekday (js/Date. year (dec month) day))]
               (str "* " weekday ", " month-pad "/" day-pad "/" year "\n\n")))
           (range 1 (inc last-day)))
         (apply str))))

(defn create-month-journal-if-not-exists
  [repo-url]
  (let [repo-dir (git/get-repo-dir repo-url)
        path (util/current-journal-path)
        file-path (str "/" path)
        default-content (default-month-journal-content)]
    (p/let [_ (-> (fs/mkdir (str repo-dir "/journals"))
                  (p/catch identity))
            file-exists? (fs/create-if-not-exists repo-dir file-path default-content)]
      (when-not file-exists?
        (git-add-commit repo-url path "create a month journal" default-content)))))

(defn load-all-contents!
  [repo-url files ok-handler]
  (let [files (only-text-formats files)]
    (p/let [contents (p/all (doall
                             (for [file files]
                               (load-file repo-url file))))]
      (ok-handler
       (zipmap files contents)))))

(defn load-repo-to-db!
  [repo-url files]
  (set-state-kv! :repo/loading-files? false)
  (set-state-kv! :repo/importing-to-db? true)
  (load-all-contents!
   repo-url
   files
   (fn [contents]
     (let [headings (db/extract-all-headings repo-url contents)]
       (db/reset-contents-and-headings! repo-url contents headings)
       (set-state-kv! :repo/importing-to-db? false)))))

(defn load-db-and-journals!
  [repo-url remote-changed? first-clone?]
  (when (or remote-changed? first-clone?)
    (p/let [files (load-files repo-url)
            loaded (load-repo-to-db! repo-url files)
            _ (create-month-journal-if-not-exists repo-url)]
      (when (or remote-changed?
                (empty? (:latest-journals @state/state)))
        (set-latest-journals!)))))

(defn pull
  [repo-url token]
  (when (and (nil? (:git/error @state/state))
             (nil? (:git/status @state/state)))
    (let [remote-changed? (atom false)
          latest-commit (:git/latest-commit @state/state)]
      (p/let [result (git/fetch repo-url token)
              {:keys [fetchHead]} (bean/->clj result)
              merge-result (do
                             (when (or
                                    (nil? latest-commit)
                                    (and latest-commit (not= fetchHead latest-commit)))
                               (reset! remote-changed? true))
                             (set-latest-commit! fetchHead)
                             (git/merge repo-url))]
        (load-db-and-journals! repo-url @remote-changed? false)))))

(defn periodically-pull
  [repo-url pull-now?]
  (when-let [token (get-github-token)]
    (when pull-now? (pull repo-url token))
    (js/setInterval #(pull repo-url token)
                    (* 60 1000))))

;; TODO: update latest commit
(defn push
  [repo-url]
  (when (and (= :should-push (:git/status @state/state))
             (nil? (:git/error @state/state)))
    (set-git-status! :push)
    (let [token (get-github-token)]
      (util/p-handle
       (git/push repo-url token)
       (fn []
         (prn "Push successfully!")
         (set-git-status! nil)
         (set-git-error! nil))
       (fn [error]
         (prn "Failed to push, error: " error)
         (set-git-status! :push-failed)
         (set-git-error! error))))))

(defn clone
  [repo]
  (let [token (get-github-token)]
    (util/p-handle
     (do
       (set-state-kv! :repo/cloning? true)
       (git/clone repo token))
     (fn []
       (db/mark-repo-as-cloned repo))
     (fn [e]
       (set-state-kv! :repo/cloning? false)
       (set-git-status! :clone-failed)
       (set-git-error! e)
       (prn "Clone failed, reason: " e)))))

(defn new-notification
  [text]
  (js/Notification. "Logseq" #js {:body text
                                  ;; :icon logo
                                  }))

(defn request-notifications
  []
  (util/p-handle (.requestPermission js/Notification)
                 (fn [result]
                   (storage/set :notification-permission-asked? true)

                   (when (= "granted" result)
                     (storage/set :notification-permission? true)))))

(defn request-notifications-if-not-asked
  []
  (when-not (storage/get :notification-permission-asked?)
    (request-notifications)))

;; notify deadline or scheduled tasks
(defn run-notify-worker!
  []
  (when (storage/get :notification-permission?)
    (let [notify-fn (fn []
                      (let [tasks (:tasks @state/state)
                            tasks (flatten (vals tasks))]
                        (doseq [{:keys [marker title] :as task} tasks]
                          (when-not (contains? #{"DONE" "CANCElED" "CANCELLED"} marker)
                            (doseq [[type {:keys [date time] :as timestamp}] (:timestamps task)]
                              (let [{:keys [year month day]} date
                                    {:keys [hour min]
                                     :or {hour 9
                                          min 0}} time
                                    now (util/get-local-date)]
                                (when (and (contains? #{"Scheduled" "Deadline"} type)
                                           (= (assoc date :hour hour :minute min) now))
                                  (let [notification-text (str type ": " (second (first title)))]
                                    (new-notification notification-text)))))))))]
      (notify-fn)
      (js/setInterval notify-fn (* 1000 60)))))

(defn show-notification!
  [text]
  (swap! state/state assoc
         :notification/show? true
         :notification/text text)
  (js/setTimeout #(swap! state/state assoc
                         :notification/show? false
                         :notification/text nil)
                 3000))

(defn alter-file
  ([path commit-message content]
   (alter-file path commit-message content true))
  ([path commit-message content redirect?]
   (let [token (get-github-token)
         repo-url (db/get-current-repo)]
     (util/p-handle
      (fs/write-file (git/get-repo-dir repo-url) path content)
      (fn [_]
        (when redirect?
          (rfe/push-state :file {:path (b64/encodeString path)}))
        (git-add-commit repo-url path commit-message content))))))

(defn clear-storage
  [repo-url]
  (js/window.pfs._idb.wipe)
  (clone repo-url))

;; TODO: utf8 encode performance
(defn check
  [heading]
  (let [{:heading/keys [repo file marker meta uuid]} heading
        pos (:pos meta)
        repo (db/entity (:db/id repo))
        file (db/entity (:db/id file))
        repo-url (:repo/url repo)
        file (:file/path file)
        token (get-github-token)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first marker "DONE")))]
        (util/p-handle
         (fs/write-file (git/get-repo-dir repo-url) file content')
         (fn [_]
           (prn "check successfully, " file)
           (git-add-commit repo-url file
                           (str marker " marked as DONE")
                           content')))))))

(defn uncheck
  [heading]
  (let [{:heading/keys [repo file marker meta]} heading
        pos (:pos meta)
        repo (db/entity (:db/id repo))
        file (db/entity (:db/id file))
        repo-url (:repo/url repo)
        file (:file/path file)
        token (get-github-token)]
    (when-let [content (db/get-file-content repo-url file)]
      (let [encoded-content (utf8/encode content)
            content' (str (utf8/substring encoded-content 0 pos)
                          (-> (utf8/substring encoded-content pos)
                              (string/replace-first "DONE" "TODO")))]
        (util/p-handle
         (fs/write-file (git/get-repo-dir repo-url) file content')
         (fn [_]
           (prn "uncheck successfully, " file)
           (git-add-commit repo-url file
                           "DONE rollbacks to TODO."
                           content')))))))


;; (defn sync
;;   []
;;   (let [[_user token repos] (get-user-token-repos)]
;;     (doseq [repo repos]
;;       (pull repo token))))

(defn set-username-email
  [name email]
  (when (and name email)
    (git/set-username-email
     (git/get-repo-dir (db/get-current-repo))
     name
     email)))

(defn get-me
  []
  (util/fetch (str config/api "me")
              (fn [resp]
                (when resp
                  (set-state-kv! :me resp)
                  (set-username-email (:name resp) (:email resp))))
              (fn [_error]
                ;; (prn "Get token failed, error: " error)
                )))

(defn set-route-match!
  [route]
  (swap! state/state assoc :route-match route))

(defn set-ref-component!
  [k ref]
  (swap! state/state assoc :ref-components k ref))

(defn set-root-component!
  [comp]
  (swap! state/state assoc :root-component comp))

(defn re-render!
  []
  (when-let [comp (get @state/state :root-component)]
    (when-not (:edit? @state/state)
      (rum/request-render comp))))

(defn db-listen-to-tx!
  []
  (d/listen! db/conn :persistence
             (fn [tx-report] ;; FIXME do not notify with nil as db-report
               ;; FIXME do not notify if tx-data is empty
               (when-let [db (:db-after tx-report)]
                 (prn "DB changed, re-rendered!")
                 (re-render!)
                 (js/setTimeout (fn []
                                  (db/persist db)) 0)))))

(defn periodically-push-tasks
  [repo-url]
  (let [token (get-github-token)
        push (fn []
               (push repo-url))]
    (js/setInterval push
                    (* 10 1000))))

(defn periodically-pull-and-push
  [repo-url {:keys [pull-now?]
             :or {pull-now? true}}]
  (periodically-pull repo-url pull-now?)
  (periodically-push-tasks repo-url))

(defn clone-and-pull
  [repo-url]
  (p/then (clone repo-url)
          (fn []
            (load-db-and-journals! repo-url false true)
            (periodically-pull-and-push repo-url {:pull-now? false}))))

(defn edit-journal!
  [content journal]
  (swap! state/state assoc
         :edit? true
         :edit-journal journal))

(defn set-journal-content!
  [uuid content]
  (swap! state/state update :latest-journals
         (fn [journals]
           (mapv
            (fn [journal]
              (if (= (:uuid journal) uuid)
                (assoc journal :content content)
                journal))
            journals))))

(defn save-current-edit-journal!
  [edit-content]
  (let [{:keys [edit-journal]} @state/state
        {:keys [start-pos end-pos]} edit-journal]
    (swap! state/state assoc
           :edit? false
           :edit-journal nil)
    (when-not (= edit-content (:content edit-journal)) ; if new changes
      (let [path (:file-path edit-journal)
            current-journals (db/get-file path)
            new-content (utf8/insert! current-journals start-pos end-pos edit-content)]
        (set-state-kv! :latest-journals (db/get-latest-journals {:content new-content}))
        (alter-file path "Auto save" new-content false)))))

(defn render-local-images!
  []
  (when-let [content-node (gdom/getElement "content")]
    (let [images (array-seq (gdom/getElementsByTagName "img" content-node))
          get-src (fn [image] (.getAttribute image "src"))
          local-images (filter
                        (fn [image]
                          (let [src (get-src image)]
                            (and src
                                 (not (or (string/starts-with? src "http://")
                                          (string/starts-with? src "https://"))))))
                        images)]
      (doseq [img local-images]
        (gobj/set img
                  "onerror"
                  (fn []
                    (gobj/set (gobj/get img "style")
                              "display" "none")))
        (let [path (get-src img)
              path (if (= (first path) \.)
                     (subs path 1)
                     path)]
          (util/p-handle
           (fs/read-file-2 (git/get-repo-dir (db/get-current-repo))
                           path)
           (fn [blob]
             (let [blob (js/Blob. (array blob) (clj->js {:type "image"}))
                   img-url (image/create-object-url blob)]
               (gobj/set img "src" img-url)
               (gobj/set (gobj/get img "style")
                         "display" "initial")))))))))

(defn load-more-journals!
  []
  (let [journals (:latest-journals @state/state)]
    (when-let [title (:title (last journals))]
      (let [before-date (last (string/split title #", "))
            more-journals (->> (db/get-latest-journals {:before-date before-date
                                                        :days 4})
                               (drop 1))
            journals (concat journals more-journals)]
        (set-state-kv! :latest-journals journals)))))

(defn request-presigned-url
  [folder filename mime-type]
  (util/post (str config/api "presigned_url")
             {:folder folder
              :filename filename
              :mime-type mime-type}
             (fn [resp]
               (prn {:resp resp}))
             (fn [_error]
               ;; (prn "Get token failed, error: " error)
               )))

(defn set-me-if-exists!
  []
  (when js/window.user
    (let [user (js->clj js/window.user :keywordize-keys true)]
      (set-state-kv! :me user))))

(defn start!
  []
  (set-me-if-exists!)
  (db/restore!)
  (set-latest-journals!)
  (db-listen-to-tx!)
  (when-let [first-repo (first (db/get-repos))]
    (db/set-current-repo! first-repo))
  (let [repos (db/get-repos)]
    (doseq [repo repos]
      (periodically-pull-and-push repo {:pull-now? true})
      (create-month-journal-if-not-exists repo))))
