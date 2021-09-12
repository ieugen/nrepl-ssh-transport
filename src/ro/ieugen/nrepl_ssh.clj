(ns ro.ieugen.nrepl-ssh
  (:require [taoensso.timbre :as timbre
             :refer [debug info error trace warn]]
            [clojure.java.io :as io])
  (:import [java.io IOException InterruptedIOException]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
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
           [org.apache.sshd.server.keyprovider SimpleGeneratorHostKeyProvider]
           [org.apache.sshd.server.shell ShellFactory]))

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


(defn my-run
  [cmd in out err exit-callback handle-cmd-line]
  (try
    (if (nil? cmd)
      (let [rdr (io/reader in :encoding "utf8")]
        (info "Another command ?" cmd)
        (loop [cmd (.readLine rdr)]
          (if (or
               (nil? cmd)
               (not (handle-cmd-line cmd)))
            nil
            (recur (.readLine rdr)))))
      (do
        (info "Command " cmd)
        (handle-cmd-line cmd)))
    (catch InterruptedIOException e
      (debug e "InterruptedIOException " cmd)
      nil)
    (catch Exception e
        ;; handle exception during commmand execution
      (let [message (str "Failed (repl-cmd) to handle " cmd (.getMessage e))]
        (try
          (let [bytes (-> message (.getBytes StandardCharsets/US_ASCII))]
            (.write err bytes))
          (catch IOException ioe
            (warn "Failed (" (-> e (.getClass) (.getSimpleName))
                  ") to write error message=" message (.getMessage ioe)))
          (finally
              ;; signal command was executed with error
            (debug "Done with " cmd " => " message)
            (.onExit exit-callback -1 message)))))
    (finally
      ;; signal command was executed successfully
      (debug "Done with cmd " cmd)
      (.onExit exit-callback 0 ""))))

(defn echo-cmd-line-handler
  [out cmd]
  (info "Echo" cmd)
  (let [cmd-nl (str cmd \newline)
        bytes (.getBytes cmd-nl StandardCharsets/UTF_8)
        res (not= "exit" cmd)]
    (debug "Send" cmd "and " res)
    (doto out
      (.write bytes)
      (.flush))
    res))

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
           (let [{:keys [callback in out err]} @state
                 handle-cmd-line (partial echo-cmd-line-handler out)]
             (debug "Running command " (CommandFactory/split cmd))
             (my-run cmd in out err callback handle-cmd-line))))))

(defn command-factory []
  (reify CommandFactory
    (createCommand [this channel command]
      (debug "Create command " channel " " command)
      (repl-command channel command))))

(defn echo-shell-factory []
  (reify ShellFactory
    (createShell [shis channel]
      (repl-command channel nil))))

(defn nrepl-ssh-server []
  (let [ssh-server (SshServer/setUpDefaultServer)]
    (doto ssh-server
      (.setHost "127.0.0.1")
      (.setPort 2222)
      (.setKeyPairProvider (SimpleGeneratorHostKeyProvider.))
      (.setPasswordAuthenticator (dumb-authenticator))
      (.setShellFactory (echo-shell-factory))
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

  (str "aa" \newline)

  0)