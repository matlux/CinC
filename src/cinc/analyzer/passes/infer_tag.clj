(ns cinc.analyzer.passes.infer-tag
  (:require [cinc.analyzer.utils :refer [postwalk]])
  (:import (clojure.lang IPersistentVector IPersistentMap
                         IPersistentSet ISeq Keyword Var
                         Symbol)
           java.util.regex.Pattern))

(defmulti -infer-tag :op)

(defmethod -infer-tag :vector
  [ast]
  (assoc ast :tag IPersistentVector))

(defmethod -infer-tag :map
  [ast]
  (assoc ast :tag IPersistentMap))

(defmethod -infer-tag :set
  [ast]
  (assoc ast :tag IPersistentMap))

(defmethod -infer-tag :seq
  [ast]
  (assoc ast :tag ISeq))

(defmethod -infer-tag :class
  [ast]
  (assoc ast :tag Class))

(defmethod -infer-tag :keyword
  [ast]
  (assoc ast :tag Keyword))

(defmethod -infer-tag :symbol
  [ast]
  (assoc ast :tag Symbol))

(defmethod -infer-tag :string
  [ast]
  (assoc ast :tag String))

;; need to specialize
(defmethod -infer-tag :number
  [ast]
  (assoc ast :tag Number))

(defmethod -infer-tag :type
  [{:keys [form] :as ast}]
  (assoc ast :tag (class form)))

(defmethod -infer-tag :record
  [{:keys [form] :as ast}]
  (assoc ast :tag (class form)))

(defmethod -infer-tag :char
  [ast]
  (assoc ast :tag Character))

(defmethod -infer-tag :regex
  [ast]
  (assoc ast :tag Pattern))

(defmethod -infer-tag :var
  [ast]
  (assoc ast :tag Var))

(defmethod -infer-tag :binding
  [{:keys [init name] :as ast}]
  (if init
    (assoc ast :tag (:tag init))
    ast))

(defmethod -infer-tag :local
  [{:keys [init name] :as ast}]
  (if init
    (assoc ast :tag (:tag init))
    ast))

(defmethod -infer-tag :default [ast] ast)
(defmethod -infer-tag :const [{:keys [op type] :as ast}]
  (assoc (-infer-tag (assoc ast :op type))
    :op op))

(defn infer-shortest-path
  [{:keys [tag form name] :as ast}]
  (if tag
    ast
    (if-let [tag (:tag (meta name))]
      (assoc ast :tag tag)
      (if-let [form-tag (:tag (meta form))]
        (assoc ast :tag form-tag)
        (-infer-tag ast)))))

(defn infer-tag [ast]
  (postwalk ast infer-shortest-path))
