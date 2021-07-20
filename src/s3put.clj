(ns s3put
  (:import
   [java.time ZonedDateTime])
  (:require
   [clojure.core.async :as a]
   [taoensso.timbre :as timbre]
   [cognitect.aws.client.api :as aws]))

(defn s3-put-chan
  [in-chan bucket file-extension]
  (let [s3 (aws/client {:api :s3})]
    (a/go-loop [data (a/<! in-chan)]
      (if-not (nil? data)
        (do
          (try
            (timbre/info (str "Uploading data to s3. bytes: " (count data)))
            (aws/invoke s3 {:op :PutObject
                            :request {:Bucket bucket
                                      :Key (str (.toEpochSecond (ZonedDateTime/now))
                                                file-extension)
                                      :LocationConstraint "us-east-1"
                                      :Body data}})
            (catch Exception e (timbre/error (str e))))
          (recur (a/<! in-chan)))
        (timbre/info "s3-put-chan shutting down")))))
