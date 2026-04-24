(ns metabase.driver.starrocks
  "StarRocks driver. Extends the MySQL driver with:
   - Connection impersonation via `EXECUTE AS <user> WITH NO REVERT`.
   - Multi-catalog sync (default_catalog + external iceberg/hive/hudi/jdbc catalogs).
     Schemas are stored as `<catalog>.<database>` and split into 3-level identifiers
     (`catalog.schema.table`) when emitted to SQL. See the Databricks driver for the
     same pattern applied to `system.information_schema`."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver.sql-jdbc.sync.describe-database :as sql-jdbc.describe-database]
   [metabase.driver.sql-jdbc.sync.describe-table :as sql-jdbc.describe-table]
   [metabase.driver.sql.query-processor :as sql.qp]
   [metabase.driver.sync :as driver.s]
   [metabase.driver-api.core :as driver-api]
   [metabase.util :as u]
   [metabase.util.honey-sql-2 :as h2x]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection ResultSet)))

(set! *warn-on-reflection* true)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Driver Registration                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(driver/register! :starrocks, :parent :mysql)

(defmethod driver/display-name :starrocks [_] "StarRocks")

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Feature Declarations                                                  |
;;; +----------------------------------------------------------------------------------------------------------------+

(doseq [[feature supported?] {:connection-impersonation               true
                              :connection-impersonation-requires-role false
                              :actions                                true
                              :actions/custom                         true
                              :actions/data-editing                   true
                              :persist-models                         true
                              :uploads                                true
                              ;; Use the modern `describe-fields` sync path rather than per-table JDBC metadata.
                              :describe-fields                        true
                              ;; Table `:schema` values are `<catalog>.<database>` (2 components).
                              :multi-level-schema                     true
                              ;; Iceberg/Hive external catalogs have no FK metadata and
                              ;; StarRocks has no FK concept internally either.
                              :foreign-keys                           false
                              :describe-fks                           false}]
  (defmethod driver/database-supports? [:starrocks feature]
    [_driver _feature _db]
    supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Identifier quoting                                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

;; When a Metabase `Table` has `:schema "iceberg_catalog.ice_demo"` and `:name "events"`,
;; the QP emits `[::h2x/identifier :table ["iceberg_catalog.ice_demo" "events"]]`. The default
;; formatter would then quote the whole string as a single entity and produce
;; `"iceberg_catalog.ice_demo"."events"`, which StarRocks parses as (single-schema).(table).
;; Split the first component on `.` so the formatter emits 3 separately-quoted parts.
(defmethod sql.qp/->honeysql [:starrocks ::h2x/identifier]
  [_driver [tag identifier-type components :as _identifier]]
  (let [components (if (and (seq components)
                            (or (and (= identifier-type :table)
                                     (>= (count components) 2))
                                (and (= identifier-type :field)
                                     (>= (count components) 3)))
                            (str/includes? (first components) "."))
                     (into (str/split (first components) #"\.") (rest components))
                     components)]
    (sql.qp/->honeysql :mysql [tag identifier-type components])))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Sync / Metadata                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; MySQL's default xf coerces 0/1 integers for boolean columns into real booleans. StarRocks returns
;; actual `Boolean` values already, so calling `pos?` on them throws. We take the full `describe-fields`
;; path ourselves below, but leave the identity xf in place defensively in case any code path still
;; routes through `describe-fields-sql`.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :starrocks
  [_driver _db & _args]
  (map identity))

;; Called by `metabase.sync.sync-metadata.tables` once per already-stored schema when the driver
;; advertises `:multi-level-schema`. StarRocks is always multi-level here (every schema is
;; `<catalog>.<database>`), so we only need to back-fill the prefix for rows created by an older
;; version of the driver that wrote plain `<database>` values. Anything already containing `.`
;; is left untouched.
(defmethod driver/adjust-schema-qualification :starrocks
  [_driver _database schema]
  (if (or (nil? schema) (str/includes? schema "."))
    schema
    (str "default_catalog." schema)))

(def ^:private excluded-catalogs
  "StarRocks internal catalogs that should never surface as synced schemas."
  #{"_statistics_"})

(def ^:private excluded-schemas
  "Per-catalog databases that should not be enumerated (bookkeeping / metadata)."
  #{"information_schema" "INFORMATION_SCHEMA"
    "sys" "_statistics_" "performance_schema"})

(defn- list-catalogs
  "Return a vector of user-visible StarRocks catalog names (includes `default_catalog` and any
   externally-configured ones — iceberg, hive, hudi, jdbc, delta, etc.)."
  [^Connection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt "SHOW CATALOGS")]
    (let [acc (java.util.ArrayList.)]
      (while (.next rs)
        (let [name (.getString rs 1)]
          (when-not (contains? excluded-catalogs name)
            (.add acc name))))
      (vec acc))))

(defn- list-tables-in-catalog
  "Enumerate tables within a single StarRocks catalog via `<catalog>.information_schema.tables`.
   Returns a set of table maps with `:schema` already prefixed by the catalog.
   `:is_writable` is set only for `default_catalog` (StarRocks internal tables); external
   catalogs (iceberg/hive/hudi/jdbc) are read-only through Metabase."
  [^Connection conn ^String catalog]
  (let [sql         (format (str "SELECT table_schema, table_name, table_type, table_comment "
                                 "FROM `%s`.`information_schema`.`tables`")
                            catalog)
        writable?   (= catalog "default_catalog")]
    (try
      (with-open [stmt (.createStatement conn)
                  rs   (.executeQuery stmt sql)]
        (let [acc (java.util.HashSet.)]
          (while (.next rs)
            (let [schema    (.getString rs "table_schema")
                  tname     (.getString rs "table_name")
                  ttype     (.getString rs "table_type")
                  ;; Only BASE TABLE rows carry a primary key usable for editing.
                  ;; VIEW / SYSTEM VIEW stay read-only regardless of catalog.
                  writable? (and writable? (= ttype "BASE TABLE"))
                  comment   (.getString rs "table_comment")]
              (when-not (contains? excluded-schemas schema)
                (.add acc
                      (cond-> {:schema      (str catalog "." schema)
                               :name        tname
                               :is_writable writable?}
                        (not (str/blank? comment)) (assoc :description comment))))))
          (set acc)))
      (catch Throwable e
        (log/warnf e "StarRocks describe-database: error listing tables in catalog `%s`" catalog)
        #{}))))

(defmethod driver/describe-database* :starrocks
  [driver database]
  {:tables
   (sql-jdbc.execute/do-with-connection-with-options
    driver database nil
    (fn [^Connection conn]
      (let [catalogs                           (list-catalogs conn)
            [inc-patterns exc-patterns]        (driver.s/db-details->schema-filter-patterns database)
            included?                          (fn [schema]
                                                 (sql-jdbc.describe-database/include-schema-logging-exclusion
                                                  inc-patterns exc-patterns schema))]
        (log/infof "StarRocks describe-database: found %d catalog(s): %s"
                   (count catalogs) (pr-str catalogs))
        (into #{}
              (comp (mapcat #(list-tables-in-catalog conn %))
                    (filter (comp included? :schema)))
              catalogs))))})

(defn- quote-identifier [s]
  (str "`" (str/replace (str s) "`" "``") "`"))

(defn- sql-string-literal [s]
  (str \' (str/replace (str s) "'" "''") \'))

(defn- fields-sql-for-catalog
  "Build the per-catalog SELECT against `<catalog>.information_schema.columns`. `schema-dbs` is the
   set of StarRocks database names (already stripped of their catalog prefix) to include; pass nil
   for no filter. Same for `table-names`."
  [^String catalog schema-dbs table-names]
  (let [where [(str "c.table_schema NOT IN ("
                    (->> excluded-schemas (map sql-string-literal) (str/join ","))
                    ")")]
        where (cond-> where
                (seq schema-dbs) (conj (str "c.table_schema IN ("
                                            (->> schema-dbs (map sql-string-literal) (str/join ","))
                                            ")"))
                (seq table-names) (conj (str "c.table_name IN ("
                                             (->> table-names (map sql-string-literal) (str/join ","))
                                             ")")))]
    (str "SELECT "
         (sql-string-literal catalog) " AS cat_, "
         "c.table_schema AS sch_, "
         "c.table_name AS tbl_, "
         "c.column_name AS col_, "
         "UPPER(c.data_type) AS dtype_, "
         "c.column_type AS ctype_, "
         "c.ordinal_position AS pos_, "
         "c.is_nullable AS nullable_, "
         "c.column_default AS default_, "
         "c.column_key AS key_, "
         "c.column_comment AS comment_ "
         "FROM " (quote-identifier catalog) ".`information_schema`.`columns` c "
         "WHERE " (str/join " AND " where) " "
         "ORDER BY c.table_schema, c.table_name, c.ordinal_position")))

(defn- split-catalog-schemas
  "Takes the cross-catalog `:schema-names` arg (strings like `\"iceberg_catalog.db1\"`) and groups
   the bare database portions by catalog. Returns a map `{catalog-name #{db1 db2}}`. If no names are
   given, returns nil."
  [schema-names]
  (when (seq schema-names)
    (reduce (fn [acc s]
              (let [parts (str/split s #"\." 2)]
                (if (= 2 (count parts))
                  (update acc (first parts) (fnil conj #{}) (second parts))
                  acc)))
            {} schema-names)))

(defn- fetch-fields
  "Run the per-catalog SELECT and collect raw field rows. Strings returned as-is; interpretation
   (boolean, base-type, etc.) is done afterwards."
  [^Connection conn ^String catalog schema-dbs table-names]
  (let [sql (fields-sql-for-catalog catalog schema-dbs table-names)]
    (try
      (with-open [stmt (.createStatement conn)
                  rs   (.executeQuery stmt sql)]
        (let [acc (java.util.ArrayList.)]
          (while (.next rs)
            (let [sch         (.getString rs "sch_")
                  nullable?   (= "YES" (.getString rs "nullable_"))
                  default-val (.getString rs "default_")
                  key-type    (.getString rs "key_")
                  comment     (.getString rs "comment_")]
              (.add acc
                    {:table-schema               (str (.getString rs "cat_") "." sch)
                     :table-name                 (.getString rs "tbl_")
                     :name                       (.getString rs "col_")
                     :database-type              (.getString rs "dtype_")
                     :database-position          (dec (.getInt rs "pos_"))
                     :database-is-nullable       nullable?
                     :database-required          (and (not nullable?)
                                                      (str/blank? default-val))
                     :database-is-auto-increment false
                     :database-is-generated      false
                     :pk?                        (= "PRI" key-type)
                     :field-comment              (when-not (str/blank? comment) comment)})))
          (vec acc)))
      (catch Throwable e
        (log/warnf e "StarRocks describe-fields: error in catalog `%s`" catalog)
        []))))

(defmethod driver/describe-fields :starrocks
  [driver database & {:keys [schema-names table-names] :as _args}]
  (sql-jdbc.execute/do-with-connection-with-options
   driver database nil
   (fn [^Connection conn]
     (let [catalogs          (list-catalogs conn)
           catalog->dbs      (split-catalog-schemas schema-names)
           rows              (into []
                                   (mapcat
                                    (fn [cat]
                                      (when (or (nil? catalog->dbs) (contains? catalog->dbs cat))
                                        (fetch-fields conn cat
                                                      (get catalog->dbs cat)
                                                      table-names))))
                                   catalogs)]
       (log/infof "StarRocks describe-fields: %d raw column rows across %d catalog(s)"
                  (count rows) (count catalogs))
       ;; Apply the standard xf so base-type / semantic-type get populated and non-nil keys are kept.
       (into [] (sql-jdbc.describe-table/describe-fields-xf driver database) rows)))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Impersonation                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver.sql/default-database-role :starrocks
  [_driver database]
  (:user (driver.conn/effective-details database)))

(defn- connection-user
  "Username the connection is currently authenticated as (strips the `@host` suffix)."
  [^Connection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt "SELECT CURRENT_USER()")]
    (when (.next rs)
      (-> (.getString rs 1)
          (str/replace #"^'" "")
          (str/replace #"'@.*$" "")))))

(defmethod driver/set-role! :starrocks
  [_driver ^Connection conn role]
  (let [current (connection-user conn)]
    (when (and current (not= current role))
      (with-open [stmt (.createStatement conn)]
        (.execute stmt (format "EXECUTE AS '%s' WITH NO REVERT" role))))))

(defn- resolve-impersonation-role
  []
  (when-let [v (resolve 'metabase-enterprise.impersonation.driver/*impersonation-role*)]
    (deref v)))

(defn- resolve-db
  [db-or-id-or-spec]
  (cond
    (integer? db-or-id-or-spec)
    (driver-api/with-metadata-provider db-or-id-or-spec
      (driver-api/database (driver-api/metadata-provider)))

    (u/id db-or-id-or-spec)
    db-or-id-or-spec

    :else nil))

(defmethod sql-jdbc.execute/do-with-connection-with-options :starrocks
  [driver db-or-id-or-spec options f]
  (let [impersonation-role (resolve-impersonation-role)]
    (if impersonation-role
      (let [db      (resolve-db db-or-id-or-spec)
            details (driver.conn/effective-details db)
            spec    (sql-jdbc.conn/connection-details->spec driver details)]
        (sql-jdbc.execute/do-with-resolved-connection
         driver
         spec
         options
         (fn [^Connection conn]
           (driver/set-role! driver conn impersonation-role)
           (sql-jdbc.execute/set-best-transaction-level! driver conn)
           (when-let [session-timezone (:session-timezone options)]
             (sql-jdbc.execute/set-time-zone-if-supported! driver conn session-timezone))
           (f conn))))
      ((get-method sql-jdbc.execute/do-with-connection-with-options :sql-jdbc)
       driver db-or-id-or-spec options f))))
