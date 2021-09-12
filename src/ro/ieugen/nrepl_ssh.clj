(ns ro.ieugen.nrepl-ssh
  (:require [taoensso.timbre :as timbre
             :refer [debug info error trace]])
  (:import [java.time Duration]
           [org.apache.sshd.client SshClient]
           [org.apache.sshd.client.auth.password PasswordIdentityProvider]
           [org.apache.sshd.client.session ClientSession]
           [org.apache.sshd.common.io IoServiceEventListener]
           [org.apache.sshd.common.session SessionListener]
           [org.apache.sshd.common.util GenericUtils]
           [org.apache.sshd.common.util.threads ThreadUtils]
           [org.apache.sshd.server SshServer]
           [org.apache.sshd.server.auth.password PasswordAuthenticator]
           [org.apache.sshd.server.command CommandFactory Command]
           [org.apache.sshd.server.keyprovider SimpleGeneratorHostKeyProvider]))

(defonce ssh-server (atom nil))
(defonce ssh-client (atom nil))

(defn dumb-authenticator []
  (reify PasswordAuthenticator
    (authenticate [this username password session]
      (debug "Auth request " username " " password "  " session)
      true)))

(defn io-service-event-listener []
  (reify IoServiceEventListener
    (connectionEstablished [this
                            connector
                            local
                            ctx
                            remote]
      (debug "Connection established"))
    (abortEstablishedConnection [this
                                 connector
                                 local
                                 ctx
                                 remote
                                 throwableble]
      (debug throwableble "Connection aborted" connector " " local " " ctx " " remote))
    (connectionAccepted [this acceptor local remote service]
      (debug "Connection accepted" acceptor " " local " " remote " " service))
    (abortAcceptedConnection [this acceptor local remote service throwableble]
      (debug throwableble "Abort accepted connection " acceptor " " local " " remote " " service))))

(defn logging-session-listener []
  (reify SessionListener
    (sessionEstablished [this session]
      (debug "Session established" session))
    (sessionCreated [this session]
      (debug "Session created" session))
    (sessionPeerIdentificationSend [this session line extra-lines]
      (debug "Session peer ident send " session " " line " " extra-lines))
    (sessionPeerIdentificationLine [this session line extra-lines]
      (debug "Session peer ident line " session " " line " " extra-lines))
    (sessionPeerIdentificationReceived [this session version extra-lines]
      (debug "Session peer ident received " session " " version " " extra-lines))
    (sessionEvent [this session event]
      (debug "Session event " session " " event))
    (sessionException [this session throwable]
      (debug throwable "Session exception " session))
    (sessionDisconnect [this session reason msg language initiator]
      (debug "Session disconnect " session " " reason " " msg " " language " " initiator))
    (sessionClosed [this session]
      (debug "Session closed" session))))

(defn- pool-prefix
  [^String cmd]
  (if (GenericUtils/isEmpty cmd) "repl-command" (-> cmd
                                                    (.replace " " "_")
                                                    (.replace "/" ":"))))

(defn- pool-name
  [^String cmd]
  (str (pool-prefix cmd) "-" (Math/abs (bit-and (System/nanoTime) 0xFFFFFF))))

(defn repl-command
  "Implements ^Command interface.
   Mina SSHD will receive create a ^Command for each command executed by user."
  [channel cmd]
  (let [executors (ThreadUtils/newSingleThreadExecutor (pool-name cmd))
        state (atom {:cmd cmd
                     :channel channel})]
    (reify
      Command
      (setInputStream [this in]
        (trace "setInputStream(" channel ")")
        (swap! state assoc :in in))
      (setOutputStream [this out]
        (trace "setOutputStream(" channel ")")
        (swap! state assoc :out out))
      (setErrorStream [this err]
        (trace "setErrorStream(" channel ")")
        (swap! state assoc :err err))
      (setExitCallback [this callback]
        (trace "setExitCallback(" channel ")")
        (swap! state assoc :callback callback))
      (start [this channel env]
        (swap! state assoc :env env)
        (try
          (debug "start(" channel ") starting runner for command="  cmd)
          (let [future (.submit executors this)]
            (swap! state assoc :cmd-future future))
          (catch RuntimeException e
            (error e "start(" channel ") Failed (repl-command) to start command " cmd ": "))))

      (destroy [this channel]
        (debug "destroy(" channel ") ?")
        (let [cmd-future (:cmd-future @state)
              should-cancel (and cmd-future
                                 ;; TODO: check current thread ?!
                                 (not (.isDone cmd-future)))]
          (when should-cancel
            (let [result (.cancel cmd-future true)]
              (debug "destroy(" channel ")[" this "] - cancel pending future=" result)))
          (swap! state :cmd-future nil)
          (when-not (.isShutdown executors)
            (let [runners (.shutdownNow executors)
                  size (.size runners)]
              (debug "destroy(" channel ")[" this "] - shutdown executors service - runners count=" size)))))

      Runnable
      (run [this]
           (let [callback (:callback @state)]
             (debug "Running command " (CommandFactory/split cmd))
             (.onExit callback 0))))))

(defn command-factory []
  (reify org.apache.sshd.server.command.CommandFactory
    (createCommand [this channel command]
      (debug "Create command " channel " " command)
      (repl-command channel command))))

(defn nrepl-ssh-server []
  (let [ssh-server (SshServer/setUpDefaultServer)]
    (doto ssh-server
      (.setHost "127.0.0.1")
      (.setPort 2222)
      (.setKeyPairProvider (SimpleGeneratorHostKeyProvider.))
      (.setPasswordAuthenticator (dumb-authenticator))
      ;; (.setShellFactory (shell-factory))
      (.setCommandFactory (command-factory))
      (.setIoServiceEventListener (io-service-event-listener))
      (.addSessionListener (logging-session-listener))
      (.start))
    ssh-server))

(comment


  (defn nrepl-ssh-client []
    (let [^SshClient ssh-client (SshClient/setUpDefaultClient)]
      (doto ssh-client
        (.setPasswordIdentityProvider PasswordIdentityProvider/EMPTY_PASSWORDS_PROVIDER)
        (.start)))
    ssh-client)

  (defn send [client]
    (with-open [^ClientSession session (-> client
                                           (.connect "ieugen" "127.0.0.1" 2222)
                                           (.verify (Duration/ofMinutes 2))
                                           (.getSession))]
      (doto session
        (.addPasswordIdentity "empty")
        (-> (.auth)
            (.verify (Duration/ofMinutes 2))))))

  (reset! ssh-server (nrepl-ssh-server))

  (reset! ssh-client (nrepl-ssh-client))

  (send @ssh-client)

  (.stop @ssh-client)

  (.stop @ssh-server)


  (let [cmd "hello my little / friend"]
    (-> cmd
        (.replace " " "_")
        (.replace "/" ":")))

  (Math/abs (bit-and (System/nanoTime) 0xFFFFFF))

  0)