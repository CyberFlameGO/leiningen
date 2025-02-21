(ns leiningen.repl
  "Start a repl session either with the current project or standalone."
  (:require [clojure.set]
            [clojure.main]
            [clojure.string :as s]
            [clojure.java.io :as io]
            nrepl.ack
            nrepl.config
            nrepl.server
            [nrepl.transport :as transport]
            [cemerick.pomegranate :as pomegranate]
            [leiningen.core.eval :as eval]
            [leiningen.core.main :as main]
            [leiningen.core.utils :as utils]
            [leiningen.core.user :as user]
            [leiningen.core.project :as project]
            [leiningen.core.classpath :as classpath]
            [leiningen.trampoline :as trampoline]))

(defn- repl-port-file-vector
  "Returns the repl port file for this project as a vector."
  [project]
  (if-let [root (:root project)]
    [root ".nrepl-port"]
    [(user/leiningen-home) "repl-port"]))

(defn- repl-port-file-path
  "Returns the repl port file path for this project."
  [project]
  (.getPath (apply io/file (repl-port-file-vector project))))

(defn lookup-opt [opt-key opts]
  (second (drop-while #(not= % opt-key) opts)))

(defn opt-host [opts]
  (lookup-opt ":host" opts))

(defn opt-port [opts]
  (if-let [port (lookup-opt ":port" opts)]
    (Integer/valueOf port)))

(defn opt-transport [opts]
  (if-let [transport (lookup-opt ":transport" opts)]
    (utils/require-resolve transport)))

(defn opt-greeting-fn [opts]
  (if-let [greeting-fn (lookup-opt ":greeting-fn" opts)]
    (utils/require-resolve greeting-fn)))

(defn ack-port [project]
  (if-let [p (or (user/getenv "LEIN_REPL_ACK_PORT")
                 (-> project :repl-options :ack-port)
                 (:ack-port nrepl.config/config))]
    (Integer/valueOf p)))

(defn repl-port [project]
  (Integer/valueOf (or (user/getenv "LEIN_REPL_PORT")
                       (-> project :repl-options :port)
                       (:port nrepl.config/config)
                       0)))

(defn repl-host [project]
  (or (user/getenv "LEIN_REPL_HOST")
      (-> project :repl-options :host)
      (:host nrepl.config/config)
      (:bind nrepl.config/config)
      "127.0.0.1"))

(defn repl-transport [project]
  (if-let [transport (or (user/getenv "LEIN_REPL_TRANSPORT")
                         (-> project :repl-options :transport)
                         (:transport nrepl.config/config))]
    (utils/require-resolve transport)))

(defn repl-greeting-fn [project]
  (if-let [greeting-fn (or (user/getenv "LEIN_REPL_GREETING_FN")
                           (-> project :repl-options :greeting-fn)
                           (:greeting-fn nrepl.config/config))]
    (utils/require-resolve greeting-fn)))

(defn client-repl-port [project]
  (let [port (repl-port project)]
    (if (= port 0)
      (try
        (-> (io/file (:root project) ".nrepl-port")
            slurp
            s/trim)
        (catch Exception _))
      port)))

(defn ensure-port [s]
  (if (re-find #":\d+($|/.*$)" s)
    s
    (main/abort "Port is required. See `lein help repl`")))

(defn is-uri? [s]
  (boolean (and (string? s) (re-find #"^https?://" s))))

(defn string-from-file [arg]
  (if-let [filename-tmp (and (seq arg) (= "@" (subs arg 0 1)) (seq (subs arg 1)))]
    (let [filename (apply str filename-tmp)
          errmsg (str "The file '" filename "' can't be read.")]
      (if-let [content (try (slurp filename)
                            (catch Exception e
                            (main/abort errmsg)))]
        (s/trim content)
        (main/abort errmsg)))
      false))

(defn connect-string [project opts]
  (let [opt (str (first opts))]
    (if-let [sx (string-from-file opt)]
      (connect-string project [sx])
      (if (is-uri? opt)
        opt
        (as-> (s/split opt #":") x
              (remove s/blank? x)
              (-> (drop-last (count x) [(repl-host project) (client-repl-port project)])
                  (concat x))
              (s/join ":" x)
              (ensure-port x))))))

(defn options-for-reply [project & {:keys [attach port scheme]}]
  (as-> (:repl-options project) opts
        (merge {:history-file (->> (if-let [root (:root project)]
                                     [root ".lein-repl-history"]
                                     [(user/leiningen-home) "repl-history"])
                                   (apply io/file)
                                   str)
                :input-stream System/in
                ;; TODO: once reply/#114 is fixed; add (user/help) back in and
                ;; move other source/javadoc/etc references into longer help.
                :welcome (list 'println (slurp (io/resource "repl-welcome")))}
               opts)
        (apply dissoc opts :init (if attach [:host :port]))
        (merge opts (cond attach {:attach (str attach)}
                          port {:port port}
                          :else {}))
        (clojure.set/rename-keys opts {:prompt :custom-prompt
                                       :welcome :custom-help})
        (if (:port opts) (update-in opts [:port] str) opts)
        (if scheme (assoc opts :scheme scheme) opts)))

(defn init-ns [{{:keys [init-ns]} :repl-options, :keys [main]}]
  (or init-ns (if main (if (namespace main)
                         (symbol (namespace main))
                         main))))

(defn- wrap-init-ns [project]
  (if-let [init-ns (init-ns project)]
    ;; set-descriptor! currently nREPL only accepts a var
    `(with-local-vars
         [wrap-init-ns#
          (fn [h#]
            ;; this needs to be a var, since it's in the nREPL session
            (with-local-vars [init-ns-sentinel# nil]
              (fn [{:keys [~'session] :as msg#}]
                (when-not (@~'session init-ns-sentinel#)
                  (swap! ~'session assoc
                         (var *ns*)
                         (try (require '~init-ns) (create-ns '~init-ns)
                              (catch Throwable t# (create-ns '~'user)))
                         init-ns-sentinel# true))
                (h# msg#))))]
       (doto wrap-init-ns#
         (nrepl.middleware/set-descriptor!
          {:requires #{(var nrepl.middleware.session/session)}
           :expects #{"eval"}})
         (alter-var-root (constantly @wrap-init-ns#))))))

(defn- handler-for [{{:keys [nrepl-middleware nrepl-handler]} :repl-options,
                     :as project}]
  (when (and nrepl-middleware nrepl-handler)
    (main/abort "Can only use one of" :nrepl-handler "or" :nrepl-middleware))
  (let [nrepl-middleware (remove nil? (concat [(wrap-init-ns project)]
                                              (or nrepl-middleware
                                                  (:middleware nrepl.config/config))))]
    (or nrepl-handler
        (:handler nrepl.config/config)
        `(nrepl.server/default-handler
           ~@(map #(if (symbol? %) (list 'var %) %) nrepl-middleware)))))

(defn- init-requires [{{:keys [nrepl-middleware nrepl-handler caught]}
                       :repl-options :as project} & nses]
  (let [defaults '[nrepl.server incomplete.core]
        nrepl-syms (->> (cons nrepl-handler nrepl-middleware)
                        (filter symbol?)
                        (map namespace)
                        (remove nil?)
                        (map symbol))
        caught (and caught (namespace caught) [(symbol (namespace caught))])]
    (for [n (concat defaults nrepl-syms nses caught)]
      (list 'quote n))))

(defn- ignore-sigint-form []
  `(when (try (Class/forName "sun.misc.Signal")
              (catch ClassNotFoundException e#))
     (try
       (sun.misc.Signal/handle
         (sun.misc.Signal. "INT")
         (proxy [sun.misc.SignalHandler] [] (handle [signal#])))
       (catch Throwable e#))))

(defn- cfg->transport-uri-scheme
  [cfg]
  (transport/uri-scheme (or (:transport cfg) #'transport/bencode)))

(defn- server-forms [project cfg ack-port start-msg?]
  [`(do (if ~(some-> (:transport cfg) meta :ns str)
          (require (symbol ~(-> (:transport cfg) meta :ns str))))
        (let [server# (nrepl.server/start-server
                       :bind ~(:host cfg)
                       :port ~(:port cfg)
                       :transport-fn ~(:transport cfg)
                       :greeting-fn ~(:greeting-fn cfg)
                       :ack-port ~ack-port
                       :handler ~(handler-for project))
              port# (:port server#)
              repl-port-file# (apply io/file ~(repl-port-file-vector project))
              ;; TODO 3.0: remove legacy repl port support.
              legacy-repl-port# (if (.exists (io/file ~(:target-path project "")))
                                  (io/file ~(:target-path project) "repl-port"))]
          (when ~start-msg?
            (println "nREPL server started on port" port# "on host" ~(:host cfg)
                     (str "- "
                          (transport/uri-scheme ~(or (:transport cfg) #'transport/bencode))
                          "://" ~(:host cfg) ":" port#)))
          (spit (doto repl-port-file# .deleteOnExit) port#)
          (when legacy-repl-port#
            (spit (doto legacy-repl-port# .deleteOnExit) port#))
          @(promise)))
   ;; TODO: remove in favour of :injections in the :repl profile
   `(do ~(when-let [init-ns (init-ns project)]
           `(try (doto '~init-ns require in-ns)
                 (catch Exception e#
                   (when-not (= '~init-ns '~'user)
                     (println e#))
                   (ns ~init-ns))))
        (when-not (resolve 'when-some)
          (binding [*out* *err*]
            (println "As of 2.8.2, the repl task is incompatible with"
                     "Clojure versions older than 1.7.0."
                     "\nYou can downgrade to 2.8.1 or use `lein trampoline run"
                     "-m clojure.main` for a simpler fallback repl."))
          (System/exit 1))
        ~@(for [n (init-requires project)]
            `(try (require ~n)
                  (catch Throwable t#
                    (println "Error loading" (str ~n ":")
                             (or (.getMessage t#) (type t#))))))
        ~(-> project :repl-options :init))])

(def reply-profile
  {:dependencies
   '[^:displace [reply "0.5.1" :exclusions [org.clojure/clojure ring/ring-core]]
     [org.nrepl/incomplete "0.1.0"]]})

(defn- trampoline-repl [project port]
  (let [init-option (get-in project [:repl-options :init])
        init-code `(do
                     ~(if-let [ns# (init-ns project)] `(in-ns '~ns#))
                     ~init-option)
        options (-> (options-for-reply project :port port)
                    (assoc :custom-eval init-code)
                    (dissoc :input-stream))
        profile (:leiningen/trampoline-repl (:profiles project)
                                            reply-profile)]
    (eval/eval-in-project
     (project/merge-profiles project [profile])
     `(do (reply.main/launch '~options) (System/exit 0))
     `(do (try (require '~(init-ns project)) (catch Exception t#))
          (require ~@(init-requires project 'reply.main))))))

(defn- ack-server
  "The server which handles ack replies."
  [transport]
  (nrepl.server/start-server
   :bind (repl-host nil)
   :handler (nrepl.ack/handle-ack nrepl.server/unknown-op)
   :transport-fn transport))

(defn nrepl-dependency? [{:keys [dependencies]}]
  (some (fn [[d]] (re-find #"nrepl" (str d))) dependencies))

;; NB: This function cannot happen in parallel (or be recursive) because of race
;; conditions in nrepl.ack.
(defn server [project cfg headless?]
  (nrepl.ack/reset-ack-port!)
  (when-not (nrepl-dependency? project)
    (main/info "Warning: no nREPL dependency detected.")
    (main/info "Be sure to include nrepl/nrepl in :dependencies"
               "of your profile."))
  (let [prep-blocker @eval/prep-blocker
        ack-port (:port (ack-server (or (:transport cfg) #'transport/bencode)))]
    (-> (bound-fn []
          (binding [eval/*pump-in* false]
            (let [[evals requires]
                  (server-forms project cfg ack-port headless?)]
              (try
                (eval/eval-in-project project
                                      `(do ~(ignore-sigint-form) ~evals)
                                      requires)
                (catch Exception e
                  (when main/*debug* (throw e))
                  (main/warn (.getMessage e)))))))
        (Thread.) (.start))
    (when project @prep-blocker)
    (when headless? @(promise))
    (if-let [repl-port (nrepl.ack/wait-for-ack
                        (get-in project [:repl-options :timeout] 60000))]
      (do (main/info "nREPL server started on port"
                     repl-port "on host" (:host cfg)
                     (str "- "
                          (cfg->transport-uri-scheme cfg)
                          "://" (:host cfg) ":" repl-port))
          repl-port)
      (main/abort "REPL server launch timed out."))))

(defn resolve-reply-launch-nrepl
  []
  (utils/require-resolve 'reply.main/launch-nrepl))

(defn client
  ([project attach]
   (client project attach {}))
  ([project attach cfg]
   (when (is-uri? attach)
     (require 'drawbridge.client))
   (pomegranate/add-dependencies :coordinates (:dependencies reply-profile)
                                 :repositories (map classpath/add-repo-auth
                                                    (:repositories project)))
   (let [launch (resolve-reply-launch-nrepl)]
     (launch (options-for-reply project
                                :attach attach
                                :scheme (cfg->transport-uri-scheme cfg))))))

(defn ^:no-project-needed repl
  "Start a repl session either with the current project or standalone.

Subcommands:

<none> -> :start

:start [:host host] [:port port]
  This will launch an nREPL server and connect a client to it.
  If the :host key is given, LEIN_REPL_HOST is set, or :host is present
  under :repl-options, that host will be attached to, defaulting to
  localhost otherwise, which will block remote connections. If the :port
  key is given, LEIN_REPL_PORT is set, or :port is present under
  :repl-options in the project map, that port will be used for
  the server, otherwise it is chosen randomly. When starting outside
  of a project, the nREPL server will run internally to Leiningen. When
  run under trampoline, the client/server step is skipped entirely; use
  the :headless command to start a trampolined server.

:headless [:host host] [:port port]
  This will launch an nREPL server and wait, rather than connecting
  a client to it.

:connect [dest]
  Connects to an already running nREPL server. Dest can be:
  - host:port -- connects to the specified host and port;
  - port -- resolves host from the LEIN_REPL_HOST environment
      variable or :repl-options, in that order, and defaults to
      localhost.
  If no dest is given, resolves the host resolved as described above
  and the port from LEIN_REPL_PORT, :repl-options, or .nrepl-port in
  the project root, in that order. Providing an argument that begins
  with @ and points to a filename containing a connect string will read
  that file and use its contents, allowing sensitive credentials to be
  kept out of the process table and shell history.

:transport [transport]
  Start nREPL using the transport referenced here, instead of using the
  default bencode transport. Useful is you want to leverage a client
  that can't handle bencode.
  If no transport is given then it will be inferred by checking
  LEIN_REPL_TRANSPORT, :repl-options, or .nrepl.edn (global one or in
  the project root), in that order.

:greeting-fn [greeting-fn]
  Function used to generate the greeting message in the REPL after the
  nREPL server has started. Useful for \"dumb\" transports like TTY, or
  when you want to send some custom message to clients on connect.
  If no greeting-fn is given then it will be inferred by checking
  LEIN_REPL_GREETING_FN, :repl-options, or .nrepl.edn (global one or in
  the project root), in that order.

For connecting to HTTPS repl servers add [nrepl/drawbridge \"0.2.1\"]
to your :plugins list.

Note: the :repl profile is implicitly activated for this task. It cannot be
deactivated, but it can be overridden."

  ([project] (repl project ":start"))
  ([project subcommand & opts]
   (let [repl-profiles (project/profiles-with-matching-meta project :repl)
         project (project/merge-profiles project repl-profiles)]
     (if (= subcommand ":connect")
       (client project (doto (connect-string project opts)
                         (->> (main/info "Connecting to nREPL at"))))
       (let [cfg {:host (or (opt-host opts) (repl-host project))
                  :port (or (opt-port opts) (repl-port project))
                  :transport (or (opt-transport opts) (repl-transport project))
                  :greeting-fn (or (opt-greeting-fn opts) (repl-greeting-fn project))}]
         (utils/with-write-permissions (repl-port-file-path project)
           (case subcommand
             ":start" (if trampoline/*trampoline?*
                        (trampoline-repl project (:port cfg))
                        (client project (server project cfg false) cfg))
             ":headless" (apply eval/eval-in-project project
                                (server-forms project cfg (ack-port project)
                                              true))
             (main/abort (str "Unknown subcommand " subcommand)))))))))

;; A note on testing the repl task: it has a number of modes of operation
;; which need to be tested individually:
;; * :start (normal operation)
;; * :headless (server-only)
;; * :connect (client-only)

;; These three modes should really each be tested in each of these contexts:
;; * :eval-in :subprocess (default)
;; * :eval-in :trampoline
;; * :eval-in :leiningen (:connect prolly doesn't need separate testing here)

;; Visualizing a 3x3 graph with checkboxes is an exercise left to the reader.

;; Possibly worth testing in TERM=dumb (no completion) as well as a regular
;; terminal, but that doesn't need to happen separately for each
;; subcommand. This is more about testing reply than the repl task itself.
