-- Iceberg external-catalog setup: registers `iceberg_catalog` (REST backend + MinIO S3
-- warehouse) inside StarRocks, creates a demo Iceberg database + table, and grants the
-- service account read access. Runs on top of the existing starrocks-test-setup.sql.
--
-- Usage (after `docker compose up` with iceberg-rest/minio healthy):
--   docker cp iceberg-catalog-setup.sql mb-starrocks-fe:/tmp/iceberg.sql
--   docker exec mb-starrocks-fe mysql -h127.0.0.1 -P9030 -uroot -e "source /tmp/iceberg.sql"

-- 1. Register the external catalog (REST catalog → apache/iceberg-rest-fixture, MinIO warehouse)
DROP CATALOG IF EXISTS iceberg_catalog;
CREATE EXTERNAL CATALOG iceberg_catalog PROPERTIES (
    "type"                              = "iceberg",
    "iceberg.catalog.type"              = "rest",
    "iceberg.catalog.uri"               = "http://iceberg-rest:8181",
    "iceberg.catalog.warehouse"         = "s3://warehouse/",
    "aws.s3.endpoint"                   = "http://minio:9000",
    "aws.s3.access_key"                 = "admin",
    "aws.s3.secret_key"                 = "password",
    "aws.s3.enable_path_style_access"   = "true",
    "aws.s3.use_instance_profile"       = "false"
);

-- 2. Create a demo Iceberg database and an Iceberg-native table.
SET CATALOG iceberg_catalog;
CREATE DATABASE IF NOT EXISTS ice_demo;
USE ice_demo;

DROP TABLE IF EXISTS events;
CREATE TABLE events (
    id       BIGINT,
    user_id  BIGINT,
    event_ts DATETIME,
    payload  STRING
);

INSERT INTO events VALUES
    (1, 100, '2026-04-01 10:00:00', 'first'),
    (2, 101, '2026-04-02 11:30:00', 'second'),
    (3, 100, '2026-04-03 09:15:00', 'third'),
    (4, 102, '2026-04-03 14:45:00', 'fourth');

-- 3. Grant the service account + impersonation targets USAGE on the catalog and SELECT on the
--    demo database. `GRANT ... IN ALL DATABASES IN CATALOG ...` is rejected by the parser in
--    StarRocks 4.0, so we grant per-database with `SET CATALOG` first.
GRANT USAGE ON CATALOG iceberg_catalog TO USER 'metabase_svc'@'%';
GRANT USAGE ON CATALOG iceberg_catalog TO USER 'analyst_alice'@'%';
GRANT USAGE ON CATALOG iceberg_catalog TO USER 'analyst_bob'@'%';

SET CATALOG iceberg_catalog;
GRANT SELECT ON ALL TABLES IN DATABASE ice_demo TO USER 'metabase_svc'@'%';
GRANT SELECT ON ALL TABLES IN DATABASE ice_demo TO USER 'analyst_alice'@'%';
GRANT SELECT ON ALL TABLES IN DATABASE ice_demo TO USER 'analyst_bob'@'%';

SET CATALOG default_catalog;

-- 4. Sanity checks.
SHOW CATALOGS;
SELECT * FROM iceberg_catalog.ice_demo.events ORDER BY id;
