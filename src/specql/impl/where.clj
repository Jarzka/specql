(ns specql.impl.where
  "SQL WHERE clause generation"
  (:require [specql.impl.util :refer :all]
            [specql.impl.registry :refer [composite-type]]
            [specql.op :as op]
            [clojure.string :as str]))

(defn sql-where
  ([table-info-registry path->table record]
   (sql-where table-info-registry path->table record []))
  ([table-info-registry path->table record path-prefix]
   (if (op/combined-op? record)
     ;; OR/AND on the top level
     (let [{combine-with :combine-with records :ops} record]
       (loop [sql []
              params []
              [record & records] records]
         (if-not record
           [(str "(" (str/join combine-with sql) ")")
            params]
           (let [[record-sql record-params]
                 (sql-where table-info-registry path->table record path-prefix)]
             (recur (conj sql record-sql)
                    (into params record-params)
                    records)))))
     ;; Regular map of field to value
     (let [{:keys [table alias]} (path->table path-prefix)
           table-columns (-> table table-info-registry :columns)
           add-where (fn [{:keys [:clause :parameters] :as where} column-accessor column-keyword value]
                       (if (satisfies? op/Op value)
                         ;; This is an operator, call to-sql to create SQL clause and params
                         (let [[cl params] (op/to-sql value column-accessor)]
                           (assoc where
                                  :clause (conj clause cl)
                                  :parameters (into parameters params)))
                         ;; Plain old value, assert that it is valid and create = comparison
                         (assoc where
                                :clause (conj clause (str column-accessor " = ?"))
                                :parameters (conj parameters (assert-spec column-keyword value)))))]
       (as-> (reduce
              (fn [where [column-keyword value]]
                ;; If column is a joined table, it has a mapping in path->table.
                ;; Recursively create clauses for the value
                (if (path->table (into path-prefix [column-keyword]))
                  (let [[sql params] (sql-where table-info-registry path->table value
                                                (into path-prefix [column-keyword])) ]
                    (assoc where
                           :clause (conj (:clause where) (str "(" sql ")"))
                           :parameters (into (:parameters where) params)))

                  ;; This is a column in the current table
                  (let [{col-name :name :as column} (column-keyword table-columns)]
                    (assert column
                            (str "Unknown column in where clause: no "
                                 column-keyword " in table " table))
                    (if-let [composite-columns (and
                                                (map? value)
                                                (some->> column :type
                                                         (composite-type table-info-registry)
                                                         table-info-registry
                                                         :columns))]
                      ;; composite type: add all fields as separate clauses
                      (reduce (fn [where [kw val]]
                                (assert (composite-columns kw)
                                        (str "Unknown column in where clause: no "
                                             kw " in composite type "
                                             (composite-type table-info-registry (:type column))))
                                (add-where where
                                           (str "(" alias ".\"" col-name "\").\""
                                                (:name (composite-columns kw)) "\"")
                                           kw val))
                              where value)

                      ;; normal column
                      (add-where where (str alias ".\"" col-name "\"") column-keyword value)))))
              {:clause [] :parameters []}
              record) w
         (update w :clause #(str/join " AND " %))
         ((juxt :clause :parameters) w))))))
