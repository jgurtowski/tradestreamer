(ns tradier-stream
  (:require
   [clojure.data.json :as json]
   [taoensso.timbre :as timbre]
   [clojure.core.async :as a]
   [clojure.string :as string]
   [gniazdo.core :as ws]
   [clj-http.client :as http]))

(def TRADIER-CREATE-SESSION-URL "https://api.tradier.com/v1/markets/events/session")

(def TRADIER-OPTIONS-URL "https://api.tradier.com/v1/markets/options/lookup")

(def TRADIER-WS-URL "wss://ws.tradier.com/v1/markets/events")

(defn get-tradier-session-id
  [auth-key]
  (let [ws-session-info (http/post TRADIER-CREATE-SESSION-URL
                                   {:headers {:Authorization (str "Bearer " auth-key)
                                              :Accept "application/json"}})
        info-json (json/read-str (:body ws-session-info) :key-fn keyword)]
    (:sessionid (:stream info-json))))

;(def ws-session
;  (get-tradier-session-id tradier-key))

(defn get-ws-connection
  [symbols tradier-session-id]
  (let [str-symbols (string/join "," symbols)]
    (timbre/info (str "Setting up Tradier connection for symbols: " str-symbols)))

  (let [out-chan (a/chan (a/dropping-buffer 100))
        chan-status (a/chan)
        ws-payload (json/write-str {:symbols symbols
                                    :sessionid tradier-session-id
                                    :linebreak true
                                    :filter ["trade", "tradex"]})
        socket (ws/connect TRADIER-WS-URL
                 :on-receive (fn [str-data]
                               (if-not (a/put! out-chan str-data)
                                 (a/put! chan-status ::close)))

                 :on-close (fn [status-code msg]
                             (timbre/info (str "Closing WS Connection: Code: "
                                               status-code
                                               " : "
                                               msg))
                             (timbre/info "Closing tradier-stream out-chan")
                             (a/close! out-chan); does closing again hurt?
                             (a/put! chan-status ::release))
                 :on-error (fn [error]
                             (timbre/error (str "WS: " error))
                             (timbre/info "Closing tradier-stream out-chan")
                             (a/close! out-chan)
                             (a/put! chan-status ::close))
                 )]
    (ws/send-msg socket ws-payload)

    ;;if out-chan is closed, we want to clean-up our ws client
    ;;sacrafice a thread to wait
    (a/go
      (if (identical? ::close (a/<! chan-status))
          (ws/close socket))
      (a/close! chan-status)) ;close the status chan regardless
    out-chan))

;; (def ochan
;;   (get-ws-connection ["PACB", "SPY"] ws-session))

;; (a/go-loop [data (a/<! ochan)]
;;   (timbre/info data)
;;   (if-not (nil? data)
;;     (recur (a/<! ochan))))


;(a/close! ochan)
