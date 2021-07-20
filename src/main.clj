(ns main
  (:require
   [clojure.core.async :as a]
   [s3put]
   [tradier-stream :as ts]
   [bzip-chan]))

;TODO get this from secret store
(def tradier-key "vUv4Oy4kJQtDlkSEK3nfGKZUqQa3")

(def tradier-session-id
  (ts/get-tradier-session-id tradier-key))

(def tradier-chan
  (ts/get-ws-connection ["SPY" "PACB"] tradier-session-id))

(def bzipper-chan
  (bzip-chan/bzipper tradier-chan 5000))


(s3put/s3-put-chan bzipper-chan "tradedata" ".ndjson.bz2")

;(a/close! tradier-chan)
