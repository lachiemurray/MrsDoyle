(ns mrs-doyle-jr.core
  (:require
   [mrs-doyle-jr.conversation :as conv]
   [mrs-doyle-jr.actions :as action]
   [mrs-doyle-jr.stats :as stats]
   [mrs-doyle-jr.util :refer :all]
   [mrs-doyle-jr.web :as web]
   [quit-yo-jibber :as jabber]
   [quit-yo-jibber.presence :as presence]
   [overtone.at-at :as at]
   [clojure.string :as s]
   [clojure.stacktrace :refer [print-stack-trace]]
   [clojure.pprint :refer [pprint]]
   [somnium.congomongo :as mongo]
   [ring.adapter.jetty :refer [run-jetty]]
   [taoensso.timbre :as timbre :refer [debug info error]]
   [taoensso.timbre.appenders.irc :refer [irc-appender]]
   [taoensso.timbre.appenders.socket :refer [socket-appender]]))

(defn ppstr [o]
  (with-out-str (pprint o)))

(defn log-error! [state e]
  (let [trace (with-out-str (print-stack-trace e))]
    (mongo/insert! :errors {:date (java.util.Date.)
                            :exception (ppstr e)
                            :state (ppstr state)
                            :stacktrace trace})
    (error e (ppstr state))))

(def default-state {:initiator nil
                    :informed #{}
                    :drinkers #{}
                    :setting-prefs #{}
                    :tea-countdown false
                    :double-jeopardy nil
                    :last-round 0
                    :actions []})

(def state (agent default-state
                  :error-mode :continue
                  :error-handler log-error!
                  :validator #(and (set? (:informed %))
                                   (set? (:drinkers %))
                                   (set? (:setting-prefs %))
                                   (or (string? (:initiator %))
                                       (nil?    (:initiator %)))
                                   (or (string? (:double-jeopardy %))
                                       (nil?    (:double-jeopardy %)))
                                   (vector? (:actions %)))))

(def config (atom nil))
(def at-pool (atom nil))
(def connection (atom nil))

(defn get-person! [addr]
  (try (mongo/insert! :people {:_id addr
                               :newbie true
                               :askme true})
       (catch com.mongodb.MongoException$DuplicateKey e
         (mongo/fetch-by-id :people addr))))

(defn build-well-volunteered-message [maker prefs]
  (let [others-prefs (into {} (filter (comp (partial not= maker) first) prefs))
        had-today (stats/get-cups-drunk (this-morning) (keys prefs))]
    (str
     (conv/well-volunteered)
     "\n"
     (apply str (map (fn [[k v]] (format " * %s: %s\n" (get-salutation k) v))
                     (sort others-prefs)))
     (if (empty? had-today)
       (conv/first-cup-of-the-day)
       (let [others-had (vec (sort (map get-salutation
                                        (filter (partial not= maker)
                                                (keys had-today)))))
             all-had (if-not (had-today maker) others-had (conj others-had "you"))]
         (if (empty? others-had)
           (conv/remember-your-cup)
           (if (= 1 (count all-had))
             (conv/had-today-singular-arg (join-with-and all-had))
             (conv/had-today-plural-arg (join-with-and all-had)))))))))

(defn build-available-reply [addr]
  (let [connected (jabber/available @connection)
        available (mongo/fetch :people
                               :where {:askme true}
                               :only [:_id])
        people (filter (partial not= addr)
                       (map :_id available))]
    (reduce str
            (conv/available)
            (map #(format "\n * %s" (get-salutation %))
                 people))))

(defn build-drunk-most-reply []
  (let [result (mongo/aggregate :cups
                                ;{:$match {:date last-week/month etc}}
                                {:$group {:_id :$drinker
                                          :drunk {:$sum 1}}}
                                {:$sort {:drunk -1}}
                                {:$limit 3})]
    (reduce str
            (conv/greediest)
            (map #(format "\n * %s (%d cup%s)"
                          (get-salutation (:_id %))
                          (:drunk %)
                          (if (= 1 (:drunk %)) "" "s"))
                 (:result result)))))

(defn build-made-most-reply []
  (let [result (mongo/aggregate :rounds
                                ;{:$match {:date last-week/month etc}}
                                {:$group {:_id :$maker
                                          :cups {:$sum :$cups}
                                          :rounds {:$sum 1}}}
                                {:$sort (array-map :cups -1 :rounds -1)}
                                {:$limit 3})]
    (reduce str
            (conv/industrious)
            (map #(format "\n * %s (%d cup%s in %d round%s)"
                          (get-salutation (:_id %))
                          (:cups %)
                          (if (= 1 (:drunk %)) "" "s")
                          (:rounds %)
                          (if (= 1 (:rounds %)) "" "s"))
                 (:result result)))))

(defn build-stats-reply [addr]
  (let [stats ((stats/get-user-stats [addr]) addr)
        [drunk made] (map #(int (+ 0.5 %)) stats)
        rounds (mongo/fetch-count :rounds
                                  :where {:maker addr})]
    (format "You have drunk %d cup%s of tea, and made %d in %d round%s. For more lovely numbers, have a look at http://whitbury:8080/"
            drunk (if (= 1 drunk) "" "s")
            made
            rounds (if (= 1 rounds) "" "s"))))

(defn build-ratio-reply [best?]
  (let [results (stats/get-drinker-luck :only (if best? :lucky :unlucky)
                                        :limit 4)]
    (reduce str
            (if best? (conv/luckiest) (conv/unluckiest))
            (map (fn [[id r]] (format "\n * %s (%.2f)"
                                     (get-salutation id)
                                     (double r)))
                 results))))

(defn append-actions [state & actions]
  (apply update-in state [:actions] conj actions))

(defn process-actions! [state conn]
  (doseq [action (:actions state)]
    (try (when action
           (action conn))
         (catch Exception e
           (log-error! state e))))
  (assoc state :actions []))

(defn tea-round-actions [maker prefs]
  (map #(action/send-message
         %
         (if (nil? maker)
           (conv/on-your-own)
           (if (= % maker)
             (build-well-volunteered-message maker prefs)
             (conv/other-offered-arg (get-salutation maker)))))
       (keys prefs)))

(defn select-by-weight [options weights]
  (let [cweight (reductions + weights)
        total   (last cweight)
        r       (rand total)]
    (if (zero? total)
      (rand-nth options)
      (first (drop-while nil?
              (map #(when (>= % r) %2)
                   cweight options))))))

(defn weight [fairness stats]
  (let [[drunk made] (or stats [0 0])]
    (Math/pow (/ drunk
                 (max 1 made))
              fairness)))

(defn select-tea-maker [dj drinkers]
  (when (> (count drinkers) 1)
    (let [potential (filter (partial not= dj) drinkers)
          stats (stats/get-user-stats potential)
          weights (map (comp (partial weight
                                      (:fairness-factor @config 1.0))
                             stats)
                       potential)
          maker (select-by-weight potential weights)]
      (info (apply str "Tea round (" dj ")"
                   (map #(format "\n %s %s (%s: %.3f)"
                                 (if (= maker %1) ">" "-")
                                 %1 (or %2 [0 0]) %3)
                        potential (map stats potential) weights)))
      maker)))

(defn process-tea-round [state]
  (let [dj (:double-jeopardy state)
        ; Convert set to vector, shuffle to protect against any bias in selection.
        drinkers (shuffle (:drinkers state))
        maker (select-tea-maker dj drinkers)
        temp (mongo/fetch-by-ids :people drinkers
                                 :only [:_id :teaprefs])
        prefs (reduce #(assoc % (:_id %2) (:teaprefs %2))
                      {} temp)]
    (-> state
        (append-actions (when maker (action/log-stats (:initiator state)  maker drinkers
                                                      (get-in @config [:jabber :username]))))
        (#(apply append-actions %
                 (tea-round-actions maker prefs)))
        (assoc :initiator nil
               :double-jeopardy (or maker dj)
               :tea-countdown false
               :drinkers #{}
               :informed #{}
               :setting-prefs #{}
               :last-round (at/now)))))

(defn handle-tea-round [conn]
  (send state process-tea-round)
  (send state process-actions! @connection))

(defn provided-prefs [state addr text]
  (let [clauses (s/split text #", *" 2)]
    (when (and (> (count clauses) 1)
               (conv/tea-prefs? (last clauses)))
      (append-actions state
                      (action/update-person addr
                                            :teaprefs
                                            (last clauses))))))

(defn ask-for-prefs [state person]
  (when (not (:teaprefs person))
    (-> state
        (update-in [:setting-prefs] conj (:_id person))
        (append-actions
         (action/send-message (:_id person) (conv/how-to-take-it))))))

(defn how-they-like-it-clause [state person text]
  (or (provided-prefs state (:_id person) text)
      (ask-for-prefs state person)
      state))

(defn presence-message [askme addr]
  (if askme
    ""
    (conv/alone-status)))

(defn in-round [state person]
  (let [addr (:_id person)]
    (if (and (:tea-countdown state)
             (:askme person)
             (not (get-in state [:informed addr])))
      (-> state
          (update-in [:informed] conj addr)
          (append-actions
           (action/send-message addr (conv/want-tea))))
      state)))

(defn message-dbg [state person text]
  (when (= "dbg" text)
    (append-actions state
                    (action/send-message (:_id person) (ppstr state)))))

(defn message-rude [state person text]
  (when (conv/rude? text)
    (append-actions state
                    (action/send-message (:_id person) (conv/rude)))))

(defn message-gordon [state person text]
  (when (conv/gordon? text)
    (append-actions state
                    (action/send-message (:_id person) (conv/gordon)))))

(defn message-question-who [state person text]
  (when (conv/who? text)
    (cond
     (conv/available? text)
     (append-actions state (action/send-message (:_id person)
                                                (build-available-reply (:_id person))))

     (conv/most? text)
     (append-actions state
                     (cond
                      (conv/drunk? text)
                      (action/send-message (:_id person) (build-drunk-most-reply))

                      (conv/made? text)
                      (action/send-message (:_id person) (build-made-most-reply))

                      :else
                      (action/unrecognised-text (:_id person) text)))

     (conv/luckiest? text)
     (append-actions state (action/send-message (:_id person)
                                                (build-ratio-reply true)))

     (conv/unluckiest? text)
     (append-actions state (action/send-message (:_id person)
                                                (build-ratio-reply false))))))

(defn message-question-what [state person text]
  (when (and (conv/what? text)
             (conv/stats? text))
    (append-actions state
                    (action/send-message (:_id person) (build-stats-reply (:_id person))))))

(defn message-go-away [state person text]
  (when (conv/go-away? text)
    (let [addr (:_id person)]
      (append-actions state
                      (action/update-person addr :askme false)
                      (action/send-presence addr (presence-message false addr))
                      (action/send-message addr (conv/no-tea-today))))))

(defn message-setting-prefs [state person text]
  (let [addr (:_id person)]
    (when (get-in state [:setting-prefs addr])
      (-> state
          (update-in [:setting-prefs] disj addr)
          (append-actions
           (action/update-person addr :teaprefs text)
           (action/send-message addr (conv/ok)))))))

(defn message-drinker [state person text]
  (let [addr (:_id person)]
    (when (and (:tea-countdown state)
               (get-in state [:drinkers addr]))
      (cond
       (conv/tea-prefs? text)
       (append-actions state
                       (action/update-person addr :teaprefs text)
                       (action/send-message addr (conv/like-tea-arg text)))

       (conv/yes? text)
       (append-actions state
                       (action/send-message addr (conv/ok)))

       (conv/no? text)
       (append-actions state
                       (action/send-message addr (conv/no-backout)))))))

(defn message-countdown [state person text]
  (when (:tea-countdown state)
    (let [addr (:_id person)]
      (cond
       (conv/yes? text)
       (-> state
           (update-in [:drinkers] conj addr)
           (append-actions
            (action/send-message addr (conv/ah-grand)))
           (how-they-like-it-clause person text))

       (conv/no? text)
       (append-actions state
                       (action/send-message addr (conv/ah-go-on)))))))

(defn message-add-person [state person text]
  (when-let [other (conv/add-person? text)]
    (append-actions state
                    (action/subscribe other)
                    (action/send-message (:_id person) (conv/add-person)))))

(defn message-tea [thestate person text]
  (when (and (not (:tea-countdown thestate))
             (conv/tea? text))
    (let [addr (:_id person)]
      (-> thestate
          (assoc :tea-countdown true)
          (assoc :initiator addr)
          (update-in [:drinkers] conj addr)
          (update-in [:informed] conj addr)
          (append-actions
           (action/send-message addr (conv/good-idea))
           (action/tea-countdown state
                                 @at-pool
                                 (:tea-round-duration @config 120)
                                 handle-tea-round))
          (how-they-like-it-clause person text)))))

(defn message-hello [state person text]
  (when (conv/hello? text)
    (append-actions state
                    (action/send-message (:_id person) (conv/greeting)))))

(defn message-yes [state person text]
  (when (conv/yes? text)
    (append-actions state
                    (if (< (- (at/now)
                              (:last-round state))
                           (* 1000 (:just-missed-duration @config 60)))
                      (action/send-message (:_id person) (conv/just-missed))
                      (action/unrecognised-text (:_id person) text)))))

(defn message-help [state person text]
  (when (conv/help? text)
    (append-actions state
                    (action/send-message (:_id person) (conv/help)))))

(defn message-huh [state person text]
  (append-actions state
                  (action/unrecognised-text (:_id person) text)))

(defn handle-message [conn msg]
  (when-let [text (:body msg)]
    (let [addr (:from msg)
          person (get-person! addr)]
      (info (format "Received (%s): %s" addr text))
      (send state append-actions
            (action/update-person addr :askme true))
      (send state in-round person)
      (send state #(some (fn [f] (f % person text))
                         [message-dbg
                          message-rude
                          message-go-away
                          message-setting-prefs
                          message-gordon
                          message-question-who
                          message-question-what
                          message-drinker
                          message-countdown
                          message-add-person
                          message-tea
                          message-hello
                          message-help
                          message-yes
                          message-huh]))
      (send state process-actions! conn)
      nil)))

(defn presence-status [state status person]
  (let [available (and (:askme person)
                       (not (conv/away? status)))]
    (append-actions state
                    (action/send-presence (:_id person)
                                          (presence-message available
                                                            (:_id person))))))

(defn presence-newbie [state person]
  (if (:newbie person)
    (append-actions state
                    (action/send-message (:_id person) (conv/newbie-greeting))
                    (action/update-person (:_id person) :newbie false))
    state))

(defn handle-presence [presence]
  (let [addr (:jid presence)
        person (get-person! addr)
        status (or (:status presence) "")]
    (debug (format "Presence %s %s (%s): '%s'"
                   (if (:online? presence) " online" "offline")
                   (if (:away? presence) "away" "here")
                   addr
                   status))
    (send state presence-status status person)
    (when (and (:online? presence)
               (not (:away? presence)))
      (send state presence-newbie person)
      (send state in-round person))
    (send state process-actions! @connection)))

(defn load-config! [fname]
  (swap! config (constantly (read-string (slurp fname)))))

(defn configure-logger []
  (timbre/set-config! [:timestamp-pattern] "yyyy-MM-dd HH:mm:ss")
  (when-let [sock-conf (:socket-logger @config)]
    (timbre/set-config! [:shared-appender-config :socket] sock-conf)
    (timbre/set-config! [:appenders :socket-appender] socket-appender))
  (when-let [irc-conf (:irc-logger @config)]
    (timbre/set-config! [:shared-appender-config :irc] irc-conf)
    (timbre/set-config! [:appenders :irc-appender] (assoc irc-appender
                                                     :async? true))
    (timbre/set-config! [:appenders :standard-out :min-level] :warn)))

(defn make-at-pool! []
  (swap! at-pool (constantly (at/mk-pool))))

(defn connect-mongo! [conf]
  (let [conn (mongo/make-connection (:db conf) (:args conf))]
    (mongo/set-connection! conn)))

(defn connect-jabber! [conf]
  (let [conn (jabber/make-connection conf (var handle-message))]
    (swap! connection (constantly conn))
    (presence/add-presence-listener conn (var handle-presence))
    conn))

(defn run-webserver [conf]
  (run-jetty web/wrapped-handler (merge {:join? false} conf)))

(defn connect! [& [fname]]
  (load-config! (or fname "config.clj"))
  (configure-logger)
  (make-at-pool!)
  (connect-mongo! (:mongo @config))
  (send state #(assoc % :double-jeopardy
                 (:double-jeopardy
                  (mongo/fetch-by-id :state (get-in @config [:jabber :username])
                                     :only [:double-jeopardy]))))
  (connect-jabber! (:jabber @config))
  (run-webserver (:webserver @config)))

(defn -main [& [configfile]]
  (connect! configfile)
  (info "Let's make some tea!")
  (while (.isConnected @connection)
    (Thread/sleep 100))
  (info "That's all folks!"))
