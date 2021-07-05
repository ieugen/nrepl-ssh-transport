(ns ro.ieugen.nrepl-ssh
  (:import [org.apache.sshd.server SshServer]
           [org.apache.sshd.server.shell ProcessShellFactory]
           [org.apache.sshd.server.command CommandFactory AbstractCommandSupport Command]
           [org.apache.sshd.server.keyprovider SimpleGeneratorHostKeyProvider]
           [org.apache.sshd.server.auth.password PasswordAuthenticator]
           [org.apache.sshd.server.shell InteractiveProcessShellFactory]
           [org.apache.sshd.common AttributeRepository SshConstants]
           [org.apache.sshd.common.io IoConnector IoServiceEventListener]
           [org.apache.sshd.common.session SessionListener]
           [org.apache.sshd.common.util.threads SshThreadPoolExecutor ThreadUtils]
           [java.net SocketAddress]
           [java.nio.charset StandardCharsets]))

(defonce ssh-server (atom nil))

(defn dumb-authenticator []
  (reify PasswordAuthenticator
    (authenticate [this username password session]
      (println "Auth request " username " " password "  " session)
      true)))

(defn io-service-event-listener []
  (reify IoServiceEventListener
    (connectionEstablished [this
                            connector
                            local
                            ctx
                            remote]
      (println "Connection established"))
    (abortEstablishedConnection [this
                                 connector
                                 local
                                 ctx
                                 remote
                                 throwableble]
      (println "Connection aborted" throwableble))
    (connectionAccepted [this acceptor local remote service]
      (println "Connection accepted"))
    (abortAcceptedConnection [this acceptor local remote service throwableble]
      (println "Abort accepted connection " throwableble))))

(defn logging-session-listener []
  (reify SessionListener
    (sessionEstablished [this session]
      (println "Session established"))
    (sessionCreated [this session]
      (println "Session created"))
    (sessionPeerIdentificationSend [this session line extra-lines]
      (println "Session peer ident send " line extra-lines))
    (sessionPeerIdentificationLine [this session line extra-lines]
      (println "Session peer ident line " line extra-lines))
    (sessionPeerIdentificationReceived [this session version extra-lines]
      (println "Session peer ident received " version extra-lines))
    (sessionEvent [this session event]
      (println "Session event " event))
    (sessionException [this session throwable]
      (println "Session exception " throwable))
    (sessionDisconnect [this session reason msg language initiator]
      (println "Session disconnect " reason msg))
    (sessionClosed [this session]
      (println "Session closed"))))

(comment

  (defn repl-command [channel cmd]
    (let [executor-service (ThreadUtils/newFixedThreadPool "nrepl-sshd-pool" 4)]
      (proxy [AbstractCommandSupport] [cmd executor-service]
        (run
          ([] (let [out (.getOutputStream this)
                    c (str "Run command " cmd "\n")]
                (println c)
                (doto out
                  (.write (.getBytes c StandardCharsets/UTF_8))
                  (.flush))
                (.onExit this 0)))))))

  (defn nrepl-command-factory []
    (reify org.apache.sshd.server.command.CommandFactory
      (createCommand [this channel command]
        (println "Create command " command)
        (repl-command channel command))))

  (let [instance (SshServer/setUpDefaultServer)]
    (reset! ssh-server instance)
    (doto instance
      (.setHost "127.0.0.1")
      (.setPort 2222)
      (.setKeyPairProvider (SimpleGeneratorHostKeyProvider.))
      (.setPasswordAuthenticator (dumb-authenticator))
      ;; (.setShellFactory InteractiveProcessShellFactory/INSTANCE)
      (.setCommandFactory (nrepl-command-factory))
      (.setIoServiceEventListener (io-service-event-listener))
      (.addSessionListener (logging-session-listener))
      (.start))
    ;; (java.lang.Thread/sleep java.lang.Long/MAX_VALUE)
    )

  (println "test")

  (.stop @ssh-server)

  0)