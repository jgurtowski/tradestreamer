(ns tradier-stream
  (:require
   [clojure.data.json :as json]
   [taoensso.timbre :as timbre]
   [clojure.core.async :as a]
   [clojure.string :as string]
   [gniazdo.core :as ws]
   [clj-http.client :as http]))

(def STREAM-BUFFER-SIZE 10000)

(def TRADIER-CREATE-SESSION-URL "https://api.tradier.com/v1/markets/events/session")

(def TRADIER-WS-URL "wss://ws.tradier.com/v1/markets/events")

(def TRADIER-STREAM-URL "https://stream.tradier.com/v1/markets/events")

(defn get-tradier-session-id
  [auth-key]
  (let [ws-session-info (http/post TRADIER-CREATE-SESSION-URL
                                   {:headers {:Authorization (str "Bearer " auth-key)
                                              :Accept "application/json"}})
        info-json (json/read-str (:body ws-session-info) :key-fn keyword)]
    (:sessionid (:stream info-json))))

;;was just testing with this
(defn get-stream-connection
  [symbols tradier-session-id tradier-auth-key]
  (let [out-chan (a/chan)]
    (a/go
      (let [response (http/post TRADIER-STREAM-URL {:headers {:Authorization (str "Bearer " tradier-auth-key)
                                                              :Accept "application/json"}
                                                    :form-params {:sessionid tradier-session-id
                                                                  :filter ["trade","tradex"]
                                                                  :symbols (string/join "," symbols)
                                                                  :linebreak true}
                                                    :as :reader})]
        (timbre/info (str "TRADIER CONNECTION: " (:status response)))
        (with-open [reader (:body response)]
          (dorun (for [item (line-seq reader)]
                   (do
                     (println item)
                     (a/put! out-chan item)))))
        (a/close! out-chan)))
    out-chan))

(defn get-ws-connection
  [symbols tradier-session-id]
  (let [str-symbols (string/join "," symbols)]
    (timbre/info (str "Setting up Tradier connection for "
                      (count symbols)
                      " symbols")))

  (let [out-chan (a/chan (a/dropping-buffer STREAM-BUFFER-SIZE))
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
                             (timbre/error "encountered an error closing tradier-stream out-chan")
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
