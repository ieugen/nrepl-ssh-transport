(ns ro.ieugen.nrepl-ssh
  (:import [org.apache.sshd.server SshServer]
           [org.apache.sshd.server.shell ProcessShellFactory]
           [org.apache.sshd.server.keyprovider SimpleGeneratorHostKeyProvider]
           [org.apache.sshd.server.auth.password PasswordAuthenticator]
           [org.apache.sshd.server.shell InteractiveProcessShellFactory]
           [org.apache.sshd.common AttributeRepository]
           [org.apache.sshd.common.io IoConnector IoServiceEventListener]
           [org.apache.sshd.common.session SessionListener]
           [java.net SocketAddress]))

(def server-instance (atom nil))

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

  (let [ssh-server (SshServer/setUpDefaultServer)]
    (doto ssh-server
      (.setHost "127.0.0.1")
      (.setPort 2222)
      (.setKeyPairProvider (SimpleGeneratorHostKeyProvider.))
      (.setPasswordAuthenticator (dumb-authenticator))
      (.setShellFactory InteractiveProcessShellFactory/INSTANCE)
      (.setIoServiceEventListener (io-service-event-listener))
      (.addSessionListener (logging-session-listener))
      (.start))
    (swap! server-instance (fn [o-s n-s] (when-not (nil? o-s) (.stop o-s)) n-s) ssh-server)
    (java.lang.Thread/sleep java.lang.Long/MAX_VALUE))

  (.stop @server-instance)


  0)


