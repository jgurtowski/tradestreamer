(ns bzip-chan
  (:import [java.io ByteArrayOutputStream])
  (:require
   [clojure.core.async :as a]
   [clojure.java.io :as io]
   [taoensso.timbre :as timbre]
   [clj-compress.core :as c]))

(defn bzipper
  "chunk-size (in bytes) is pre-compressed chunk size. Dealing w/ low level java is
  too big of a pain right now
  "
  [in-chan chunk-size]
  (let [out-chan (a/chan)]
    (a/go-loop [data (a/<! in-chan)
                out (ByteArrayOutputStream.)
                buffer nil]
      (if (nil? data)
        (do 
          (if (not (nil? buffer))
            (do
              (c/compress-data (.getBytes buffer) out "bzip2")
              (a/>! out-chan (.toByteArray out))))
          (timbre/info "Closing bzipper out-chan")
          (a/close! out-chan))
        (recur (a/<! in-chan)
               out
               (if (> (count buffer) chunk-size)
                 (do
                   (c/compress-data (.getBytes buffer) out "bzip2")
                   (a/>! out-chan (.toByteArray out))
                   (.reset out)
                   nil); set buffer to nil
                 (str buffer data)))))
    out-chan))

;; (def in (a/chan))
;; (def out (a/chan))

;; (bzipper in out 30)

;; (a/go-loop [data (a/<! out)
;;             n 1]
;;   (if-not (nil? data)
;;     (do
;;       (println (count data))
;;       (with-open [w (io/output-stream (str n ".txt.bz2"))]
;;         (.write w data))
;;       (recur (a/<! out) (+ 1 n)))
;;     (println "DONE")))

;; (a/put! in "HELLO")
;; (a/close! in)
