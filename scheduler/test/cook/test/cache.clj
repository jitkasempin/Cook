(ns cook.test.cache
  (:use clojure.test)
  (:require [clj-time.core :as t]
            [cook.cache :as ccache])
  (:import (com.google.common.cache Cache CacheBuilder)
           (java.util.concurrent TimeUnit)))

(defn new-cache []
  "Build a new cache"
  (-> (CacheBuilder/newBuilder)
      (.maximumSize 100)
      (.expireAfterAccess 2 TimeUnit/HOURS)
      (.build)))

(deftest test-cache
  (let [^Cache cache (new-cache)
        extract-fn #(if (odd? %) nil %)
        miss-fn #(if (> % 100) nil %)]
    ;; u1 has 2 jobs running
    (is (= 1 (ccache/lookup-cache! cache extract-fn miss-fn 1))) ; Should not be cached. Nil from extractor.
    (is (= 2 (ccache/lookup-cache! cache extract-fn miss-fn 2))) ; Should be cached.
    (is (= nil (.getIfPresent cache 1)))
    (is (= 2 (.getIfPresent cache 2)))
    (is (= nil (ccache/lookup-cache! cache extract-fn miss-fn 101))) ; Should not be cached. Nil from miss function
    (is (= nil (ccache/lookup-cache! cache extract-fn miss-fn 102))) ; Should not be cached. Nil from miss function
    (is (= nil (.getIfPresent cache 101)))
    (is (= nil (.getIfPresent cache 102)))
    (is (= 4 (ccache/lookup-cache! cache extract-fn miss-fn 4))) ; Should be cached.
    (is (= 2 (.getIfPresent cache 2)))
    (is (= 4 (.getIfPresent cache 4)))))

(deftest test-cache-expiration
  (let [^Cache cache (new-cache)
        epoch (t/epoch)
        extract-fn identity
        make-fn (fn [key offset]
                  {:val (* key 2) :cache-expires-at (->> offset t/millis (t/plus epoch))})
        miss-fn (fn [key] (make-fn key (-> key (* 1000))))
        miss-fn2 (fn [key] (make-fn key (-> key (* 1000) (+ 10000))))]
    ;; Should expire at 1000, 2000, and 3000
    (with-redefs [t/now (fn [] epoch)]
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 1)
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 2)
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 3))

    ;; None of these should be expired.
    (with-redefs [t/now (fn [] (t/plus epoch (t/millis 999)))]
      (is (= (make-fn 1 1000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 1)))
      (is (= (make-fn 2 2000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 2)))
      (is (= (make-fn 3 3000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 3))))

    ;; This should expire and be replaced
    (with-redefs [t/now (fn [] (t/plus epoch (t/millis 1001)))]
      (is (= (make-fn 1 11000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 1)))
      (is (= (make-fn 2 2000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 2)))
      (is (= (make-fn 3 3000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 3))))

    ;; This should expire and be replaced
    (with-redefs [t/now (fn [] (t/plus epoch (t/millis 2001)))]
      (is (= (make-fn 1 11000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 1)))
      (is (= (make-fn 2 12000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 2)))
      (is (= (make-fn 3 3000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 3))))))


(deftest test-cache-not-expiring
  (let [^Cache cache (new-cache)
        epoch (t/epoch)
        extract-fn identity
        make-fn (fn [key offset]
                  {:val (* key 2)})
        miss-fn (fn [key] (make-fn key (-> key (* 1000))))
        miss-fn2 (fn [key] (make-fn key (-> key (* 1000) (+ 10000))))]
    ;; These do not have the :cache-expires-at and should not expire.
    (with-redefs [t/now (fn [] epoch)]
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 1)
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 2)
      (ccache/lookup-cache-with-expiration! cache identity miss-fn 3))

    ;; None of these should be expired.
    (with-redefs [t/now (fn [] (t/plus epoch (t/millis 999)))]
      (is (= (make-fn 1 1000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 1)))
      (is (= (make-fn 2 2000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 2)))
      (is (= (make-fn 3 3000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 3))))

    ;; None of these should be expired.
    (with-redefs [t/now (fn [] (t/plus epoch (t/millis 2000001)))]
      (is (= (make-fn 1 11000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 1)))
      (is (= (make-fn 2 12000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 2)))
      (is (= (make-fn 3 3000) (ccache/lookup-cache-with-expiration! cache identity miss-fn2 3))))))