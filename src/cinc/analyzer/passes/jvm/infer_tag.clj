(ns cinc.analyzer.passes.jvm.infer-tag
  (:require [cinc.analyzer.utils :refer [arglist-for-arity]]
            [cinc.analyzer.jvm.utils :as u]))

(defmulti -infer-tag :op)
(defmethod -infer-tag :default [ast] ast)

(defmethod -infer-tag :binding
  [{:keys [init local atom] :as ast}]
  (if init
    (let [info (merge (when-let [tag (:tag init)]
                        {:tag tag})
                      (when-let [return-tag (:return-tag init)]
                        {:return-tag return-tag})
                      (when-let [arglists (:arglists init)]
                        {:arglists arglists}))]
      (swap! atom merge info)
      (merge ast info))
    ast))

(defmethod -infer-tag :local
  [{:keys [atom] :as ast}]
  (merge @atom ast))

(defmethod -infer-tag :var
  [{:keys [var] :as ast}]
  (let [{:keys [tag arglists]} (meta var)]
    ;;if (not dynamic)
    (merge ast
           (when tag
             (if (fn? @var)
               {:tag AFunction :return-tag tag}
               {:tag tag}))
           (when arglists {:arglists arglists}))))

(defmethod -infer-tag :def
  [{:keys [init var] :as ast}]
  (let [ast (assoc ast :tag Var)
        {:keys [arglists return-tag]} init]
    (merge ast
           (when arglists
             {:arglists arglists})
           (when return-tag
             {:return-tag return-tag}))))

(defmethod -infer-tag :new
  [{:keys [maybe-class class] :as ast}]
  (assoc ast :tag (or class (u/maybe-class maybe-class))))

(defmethod -infer-tag :with-meta
  [{:keys [expr] :as ast}]
  (let [{:keys [tag return-tag arglists]} expr]
    (merge ast
           (when tag
             {:tag tag})
           (when return-tag
             {:return-tag return-tag})
           (when arglists
             {:arglists arglists}))))

(defmethod -infer-tag :recur
  [ast]
  (assoc ast :loop-tag true))

(defmethod -infer-tag :do
  [{:keys [ret] :as ast}]
  (let [{:keys [tag return-tag arglists loop-tag]} ret]
    (merge ast
           (when tag
             {:tag tag})
           (when return-tag
             {:return-tag return-tag})
           (when arglists
             {:arglists arglists})
           (when loop-tag
             {:loop-tag loop-tag}))))

(defmethod -infer-tag :let
  [{:keys [body] :as ast}]
  (let [{:keys [tag return-tag arglists loop-tag]} body]
    (merge ast
           (when tag
             {:tag tag})
           (when return-tag
             {:return-tag return-tag})
           (when arglists
             {:arglists arglists})
           (when loop-tag
             {:loop-tag loop-tag}))))

(defmethod -infer-tag :letfn
  [{:keys [body] :as ast}]
  (let [{:keys [tag return-tag arglists loop-tag]} body]
    (merge ast
           (when tag
             {:tag tag})
           (when return-tag
             {:return-tag return-tag})
           (when arglists
             {:arglists arglists})
           (when loop-tag
             {:loop-tag loop-tag}))))

(defmethod -infer-tag :loop
  [{:keys [body] :as ast}]
  (let [{:keys [tag return-tag arglists loop-tag]} body]
    (merge ast
           (when tag
             {:tag tag})
           (when return-tag
             {:return-tag return-tag})
           (when arglists
             {:arglists arglists}))))

(defmethod -infer-tag :if
  [{:keys [then else] :as ast}]
  (let [[then-tag else-tag] (mapv :tag [then else])]
    (cond
     (and then-tag
          (or (:loop-tag else)
              (= then-tag else-tag)))
     (merge ast
            {:tag then-tag}
            (when-let [return-tag (:return-tag then)]
              (when (= return-tag (:return-tag else))
                {:return-tag return-tag}))
            (when-let [arglists (:arglists then)]
              (when (= arglists (:arglists else))
                {:arglists arglists})))

     (and else-tag (:loop-tag then))
     (merge ast
            {:tag else-tag}
            (when-let [return-tag (:return-tag else)]
              {:return-tag return-tag})
            (when-let [arglists (:arglists else)]
              {:arglists arglists}))

     (and (:loop-tag then) (:loop-tag else))
     (assoc ast :loop-tag true)

     :else
     ast)))

(defmethod -infer-tag :case
  [{:keys [thens default] :as ast}]
  (let [thens (conj (mapv :then thens) default)
        exprs (seq (remove :loop-tag thens))
        tag (:tag (first exprs))]
    (cond
     (and tag
          (every? #(= (:tag %) tag) exprs))
     (merge ast
            {:tag tag}
            (when-let [return-tag (:return-tag (first exprs))]
              (when (every? #(= (:return-tag %) return-tag) exprs)
                {:return-tag return-tag}))
            (when-let [arglists (:arglists (first exprs))]
              (when (every? #(= (:arglists %) arglists) exprs)
                {:arglists arglists})))

     (every? :loop-tag thens)
     (assoc ast :loop-tag true)

     :else
     ast)))

(defmethod -infer-tag :try
  [{:keys [body catches] :as ast}]
  (let [{:keys [tag return-tag arglists]} body]
    (merge ast
           (when (and tag (every? #(= % tag) (mapv (comp :tag :body) catches)))
             {:tag tag})
           (when (and return-tag (every? #(= % return-tag) (mapv (comp :return-tag :body) catches)))
             {:return-tag return-tag})
           (when (and arglists (every? #(= % arglists) (mapv (comp :arglists :body) catches)))
             {:arglists arglists}))))

(defmethod -infer-tag :fn-method
  [{:keys [form body params] :as ast}]
  (let [tag (or (:tag (meta (first form)))
                (:tag (meta (second form)))
                (:tag body))]
    (merge ast
           (when tag
             {:tag tag})
           {:arglist (with-meta (mapv :name params)
                       (when tag {:tag tag}))})))

(defmethod -infer-tag :fn
  [{:keys [local methods] :as ast}]
  (merge ast
         {:arglists (seq (map :arglist methods))
          :tag      AFunction}
         (when-let [tag (:tag (meta (:form local)))]
           {:return-tag tag})))

(defmethod -infer-tag :invoke
  [{:keys [fn args] :as ast}]
  (if (#{:var :local :fn} (:op fn))
    (let [argc (count args)
          arglist (arglist-for-arity fn argc)]
      (if-let [tag (or (:tag (meta arglist)) ;; ideally we would select the fn-method
                       (:return-tag fn)
                       (and (= :var (:op fn))
                            (:tag (meta (:var fn)))))]
        (assoc ast :tag tag)
        ast))
    (if-let [tag (:return-tag fn)]
      (assoc ast :tag tag)
      ast)))

(defmethod -infer-tag :method
  [{:keys [form body params] :as ast}]
  (let [tag (or (:tag (meta (first form)))
                (:tag (meta (second form)))
                (:tag body))]
    (if tag
      (assoc ast :tag tag)
      ast)))

(defmethod -infer-tag :reify
  [{:keys [class-name] :as ast}]
  (assoc ast :tag class-name))

(defn infer-tag
  [{:keys [tag form] :as ast}]
  (if tag
    ast
    (if-let [form-tag (and form
                           (:tag (meta form)))]
      (assoc ast :tag form-tag)
      (-infer-tag ast))))
