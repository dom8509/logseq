(ns frontend.handler.block
  (:require [frontend.util :as util]
            [clojure.walk :as walk]
            [frontend.db :as db]
            [frontend.state :as state]
            [frontend.format.mldoc :as mldoc]))

(defn blocks->vec-tree [col]
  (let [col (map (fn [h] (cond->
                          h
                           (not (:block/dummy? h))
                           (dissoc h :block/meta))) col)
        parent? (fn [item children]
                  (and (seq children)
                       (every? #(< (:block/level item) (:block/level %)) children)))]
    (loop [col (reverse col)
           children (list)]
      (if (empty? col)
        children
        (let [[item & others] col
              cur-level (:block/level item)
              bottom-level (:block/level (first children))
              pre-block? (:block/pre-block? item)]
          (cond
            (empty? children)
            (recur others (list item))

            (<= bottom-level cur-level)
            (recur others (conj children item))

            pre-block?
            (recur others (cons item children))

            (> bottom-level cur-level)      ; parent
            (let [[children other-children] (split-with (fn [h]
                                                          (> (:block/level h) cur-level))
                                                        children)

                  children (cons
                            (assoc item :block/children children)
                            other-children)]
              (recur others children))))))))

;; recursively with children content for tree
(defn get-block-content-rec
  ([block]
   (get-block-content-rec block (fn [block] (:block/content block))))
  ([block transform-fn]
   (let [contents (atom [])
         _ (walk/prewalk
            (fn [form]
              (when (map? form)
                (when-let [content (:block/content form)]
                  (swap! contents conj (transform-fn form))))
              form)
            block)]
     (apply util/join-newline @contents))))

;; with children content
(defn get-block-full-content
  ([repo block-id]
   (get-block-full-content repo block-id (fn [block] (:block/content block))))
  ([repo block-id transform-fn]
   (let [blocks (db/get-block-and-children-no-cache repo block-id)]
     (->> blocks
          (map transform-fn)
          (apply util/join-newline)))))

(defn get-block-end-pos-rec
  [repo block]
  (let [children (:block/children block)]
    (if (seq children)
      (get-block-end-pos-rec repo (last children))
      (if-let [end-pos (get-in block [:block/meta :end-pos])]
        end-pos
        (when-let [block (db/entity repo [:block/uuid (:block/uuid block)])]
          (get-in block [:block/meta :end-pos]))))))

(defn get-block-ids
  [block]
  (let [ids (atom [])
        _ (walk/prewalk
           (fn [form]
             (when (map? form)
               (when-let [id (:block/uuid form)]
                 (swap! ids conj id)))
             form)
           block)]
    @ids))

(defn collapse-block!
  [block]
  (let [repo (:block/repo block)]
    (db/transact! repo
                  [{:block/uuid (:block/uuid block)
                    :block/collapsed? true}])))

(defn collapse-blocks!
  [block-ids]
  (let [repo (state/get-current-repo)]
    (db/transact! repo
                  (map
                   (fn [id]
                     {:block/uuid id
                      :block/collapsed? true})
                   block-ids))))

(defn expand-block!
  [block]
  (let [repo (:block/repo block)]
    (db/transact! repo
                  [{:block/uuid (:block/uuid block)
                    :block/collapsed? false}])))

(defn expand-blocks!
  [block-ids]
  (let [repo (state/get-current-repo)]
    (db/transact! repo
                  (map
                   (fn [id]
                     {:block/uuid id
                      :block/collapsed? false})
                   block-ids))))

(defn pre-block-with-only-title?
  [repo block-id]
  (when-let [block (db/entity repo [:block/uuid block-id])]
    (let [properties (:page/properties (:block/page block))]
      (and (:title properties)
           (= 1 (count properties))
           (let [ast (mldoc/->edn (:block/content block) (mldoc/default-config (:block/format block)))]
             (or
              (empty? (rest ast))
              (every? (fn [[[typ break-lines]] _]
                        (and (= typ "Paragraph")
                             (every? #(= % ["Break_Line"]) break-lines))) (rest ast))))))))
