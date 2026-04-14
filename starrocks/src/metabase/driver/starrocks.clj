(ns metabase.driver.starrocks
  "StarRocks driver. Extends the MySQL driver with support for connection impersonation
   via `EXECUTE AS <user> WITH NO REVERT`."
  (:require
   [clojure.string :as str]
   [metabase.driver :as driver]
   [metabase.driver.connection :as driver.conn]
   [metabase.driver.sql :as driver.sql]
   [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
   [metabase.driver-api.core :as driver-api]
   [metabase.util :as u]
   [metabase.util.log :as log])
  (:import
   (java.sql Connection)))

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
                               ;; Writeback: StarRocks supports DML via MySQL protocol.
                               ;; - :actions/custom        → arbitrary SQL actions (INSERT works on all table types)
                               ;; - :actions/data-editing  → implicit CRUD; only works on Primary Key tables
                               ;;                             (Duplicate/Aggregate Key tables have no PK metadata
                               ;;                             and cannot be UPDATE/DELETE-d row-by-row)
                               :actions                                true
                               :actions/custom                         true
                               :actions/data-editing                   true
                               ;; StarRocks supports CREATE TABLE AS SELECT for model persistence
                               :persist-models                         true
                               ;; StarRocks supports Stream Load / INSERT for uploads
                               :uploads                                true}]
  (defmethod driver/database-supports? [:starrocks feature]
    [_driver _feature _db]
    supported?))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Sync / Metadata                                                       |
;;; +----------------------------------------------------------------------------------------------------------------+

;; The MySQL driver's `describe-fields-pre-process-xf` coerces 0/1 integers returned by MariaDB JDBC
;; for boolean columns back into real booleans. StarRocks returns actual Java Booleans already, so
;; calling `pos?` on them fails with "Boolean cannot be cast to Number". Override with identity.
(defmethod sql-jdbc.sync/describe-fields-pre-process-xf :starrocks
  [_driver _db & _args]
  (map identity))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          Connection Impersonation                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod driver.sql/default-database-role :starrocks
  [_driver database]
  ;; The "default role" is the connecting user (service account).
  ;; Used by the enterprise impersonation system as the fallback role when no impersonation is active.
  (:user (driver.conn/effective-details database)))

(defn- connection-user
  "Return the username the connection is currently authenticated as (strips the `@host` suffix).
   StarRocks' CURRENT_USER() returns values like `'metabase_svc'@'%'`."
  [^Connection conn]
  (with-open [stmt (.createStatement conn)
              rs   (.executeQuery stmt "SELECT CURRENT_USER()")]
    (when (.next rs)
      (-> (.getString rs 1)
          (str/replace #"^'" "")
          (str/replace #"'@.*$" "")))))

(defmethod driver/set-role! :starrocks
  [_driver ^Connection conn role]
  ;; `EXECUTE AS` in StarRocks requires the caller to hold the IMPERSONATE privilege on the target user.
  ;; When the target role matches the connection's own user (i.e. the EE impersonation system is just
  ;; "resetting" to the default role on a pooled connection), skip — users generally don't have
  ;; IMPERSONATE on themselves, and the connection is already authenticated as that user anyway.
  (let [current (connection-user conn)]
    (when (and current (not= current role))
      (with-open [stmt (.createStatement conn)]
        (.execute stmt (format "EXECUTE AS '%s' WITH NO REVERT" role))))))

(defn- resolve-impersonation-role
  "Resolve the current impersonation role from the enterprise dynamic var, if available.
   Returns nil if the enterprise code is not loaded or no impersonation is active."
  []
  (when-let [v (resolve 'metabase-enterprise.impersonation.driver/*impersonation-role*)]
    (deref v)))

(defn- resolve-db
  "Resolve a database instance from the db-or-id-or-spec argument."
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
      ;; IMPERSONATED PATH: create a fresh, non-pooled connection.
      ;; EXECUTE AS is non-reversible, so we cannot return this connection to the pool.
      (let [db      (resolve-db db-or-id-or-spec)
            details (driver.conn/effective-details db)
            spec    (sql-jdbc.conn/connection-details->spec driver details)]
        ;; Passing a raw spec map (instead of a Database) to do-with-resolved-connection causes it
        ;; to call jdbc/get-connection directly, bypassing the c3p0 pool.
        ;; See sql_jdbc/execute.clj do-with-resolved-connection-data-source.
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
      ;; NON-IMPERSONATED PATH: use normal pooled connection via the default :sql-jdbc implementation.
      ((get-method sql-jdbc.execute/do-with-connection-with-options :sql-jdbc)
       driver db-or-id-or-spec options f))))
