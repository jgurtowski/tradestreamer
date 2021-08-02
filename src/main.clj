(ns main
  (:require
   [clojure.core.async :as a]
   [taoensso.timbre :as timbre]
   [clj-http.client :as http]
   [clojure.data.json :as json]
   [beckon]
   [s3put]
   [tradier-stream :as ts]
   [retry]
   [bzip-chan]))

(def EXIT-SLEEP-MS 10000)

(def TRADE_BIN_SIZE 100000)

(def UNDERLYING-SYMBOLS
  ["SPY" "LADR" "BRK/B" "PACB" "BRSP"])

(def SYMBOLS-FOR-OPTIONS
  ["LADR" "PACB" "BRSP"])

(def TRADIER-OPTIONS-URL "https://api.tradier.com/v1/markets/options/lookup")

;TODO get this from secret store
(def tradier-key "vUv4Oy4kJQtDlkSEK3nfGKZUqQa3")

;TODO move to tradier lib
(defn get-options
  [underlying]
  (timbre/info (str "Fetching Options for " underlying))
  (let [options-response (http/get TRADIER-OPTIONS-URL
                                   {:headers {:Authorization (str "Bearer " tradier-key)
                                              :Accept "application/json"}
                                    :query-params {:underlying underlying}})
        options-json (json/read-str (:body options-response) :key-fn keyword)]
    (-> options-json
        :symbols
        (get 0)
        :options)))

(defn cleanup
  [chan]
  (a/close! chan)
  (Thread/sleep EXIT-SLEEP-MS)
  (System/exit 0))

(defn get-tradier-chan
  []
  (let [options-symbols (mapcat get-options SYMBOLS-FOR-OPTIONS)
        all-symbols (concat UNDERLYING-SYMBOLS options-symbols)
        tradier-session-id (ts/get-tradier-session-id tradier-key)]
    (ts/get-ws-connection all-symbols tradier-session-id)))
     
(defn -main
  []
  (let [tradier-chan (retry/ebwj-channel-keep-alive get-tradier-chan)
        tradier-chan-mult (a/mult tradier-chan)
        tradier-chan-copy (a/chan)
        tradier-chan-copy2 (a/chan)
        _ (a/tap tradier-chan-mult tradier-chan-copy)
        _ (a/tap tradier-chan-mult tradier-chan-copy2)
        bzipper-chan (bzip-chan/bzipper tradier-chan-copy TRADE_BIN_SIZE)]

    (s3put/s3-put-chan bzipper-chan "tradedata" ".ndjson.bz2")

    ;;setup our exit handler
    (reset! (beckon/signal-atom "INT")
            [(fn [] (cleanup tradier-chan))])
    (reset! (beckon/signal-atom "TERM")
            [(fn [] (cleanup tradier-chan))])
    
    ;spin and wait until chan closes
    (loop [c 0
           data (a/<!! tradier-chan-copy2)]
      (if-not (nil? data)
        (do
          (if (= 0 (mod c 100))
            (timbre/info (str "COUNT " c)))
          (recur (inc c) (a/<!! tradier-chan-copy2)))))

    (a/untap-all tradier-chan-mult)

    ;wait a little bit for everything to upload
    (Thread/sleep EXIT-SLEEP-MS)
    (timbre/info "Exiting")))

;(-main)


;(a/close! tradier-chan)
