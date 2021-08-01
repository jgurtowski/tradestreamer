(ns retry
  (:require
   [taoensso.timbre :as timbre]
   [clojure.core.async :as a]
   [clojure.math.numeric-tower :as math]))

(defn ebwj-channel-keep-alive
  "channel-fn: returns a channel, if the channel closes it will be recreated by calling channel-fn again
   condition-fn: returns true/false for whether to short circuit the retrying
  "
  [channel-fn & {:keys [base multiplier timeout jitter]
                   :or {base 500
                        multiplier 1.5
                        timeout 300000
                        jitter false}}]
  (let [out-chan (a/chan)]
    (a/go-loop
        [in-chan (channel-fn)
         time-waited 0
         retry-count 0
         data (a/<! in-chan)]

        (let [time-to-wait (+ (* base (math/expt multiplier retry-count))
                              (if jitter (rand-int base) 0))
              output-success? (if (nil? data) true (a/>! out-chan data))]
          
          (if (nil? data)
            (do
              (timbre/warn (str "Retrying Chan Creation, waiting: " time-to-wait))
              (a/<! (a/timeout time-to-wait))))
      
          (if (> time-waited timeout)
            (timbre/error (str "Retry Timeout Exceeded " timeout)))
        
          (if-not (and (<= time-waited timeout) output-success?)
            (do
              (timbre/info "Exiting Channel-Keep-Alive")
              (a/close! in-chan)
              (a/close! out-chan))
            (let [recur-params (if (nil? data)
                                 (let [new-chan (channel-fn)]
                                   [new-chan (+ time-waited time-to-wait)
                                    (inc retry-count) (a/<! new-chan)])
                                 [in-chan 0 0 (a/<! in-chan)])]
              (recur (nth recur-params 0)
                     (nth recur-params 1)
                     (nth recur-params 2)
                     (nth recur-params 3))))))
    out-chan))
      

;; (defn test-producer
;;   []
;;   (let [out-chan (a/chan)]
;;     (a/go-loop []
;;       (a/>! out-chan "HI")
;;       (a/<! (a/timeout 500))
;;       (a/>! out-chan "Bye")
;;       (a/close! out-chan))
;;     out-chan))

;; (defn test-producer2
;;   []
;;   (let [out-chan (a/chan)]
;;     (a/go-loop []
;;       (if (a/>! out-chan "HI")
;;         (recur)))
;;     out-chan))

;; (defn dead-chan
;;   []
;;   (let [out-chan (a/chan)]
;;     (a/close! out-chan)
;;     out-chan))

;; (let [out-chan (ebwj-channel-keep-alive dead-chan :timeout 10000)]
;;   (loop [count 0
;;          data (a/<!! out-chan)]
;;     (timbre/info data)
;;     (if (>= count 10)
;;       (a/close! out-chan))
;;     (if data
;;       (recur (inc count) (a/<!! out-chan)))))
  
      
  

