-- StarRocks test setup: service account + impersonation targets + RLS view.
-- Usage (after `docker compose up` is healthy):
--   docker cp starrocks-test-setup.sql mb-starrocks-fe:/tmp/setup.sql
--   docker exec mb-starrocks-fe mysql -h127.0.0.1 -P9030 -uroot -e "source /tmp/setup.sql"

-- 1. Service account that Metabase connects as
CREATE USER IF NOT EXISTS 'metabase_svc'@'%' IDENTIFIED BY 'metabase_pw';

-- 2. Impersonation target users
CREATE USER IF NOT EXISTS 'analyst_alice'@'%' IDENTIFIED BY 'alice_pw';
CREATE USER IF NOT EXISTS 'analyst_bob'@'%'   IDENTIFIED BY 'bob_pw';

-- 3. Service account can impersonate the target users
GRANT IMPERSONATE ON USER 'analyst_alice'@'%' TO USER 'metabase_svc'@'%';
GRANT IMPERSONATE ON USER 'analyst_bob'@'%'   TO USER 'metabase_svc'@'%';

-- 4. Test database + Primary Key table (required for UPDATE/DELETE + Metabase Actions)
CREATE DATABASE IF NOT EXISTS metabase_test;
USE metabase_test;

DROP TABLE IF EXISTS orders;
CREATE TABLE orders (
    id BIGINT NOT NULL,
    customer VARCHAR(100),
    amount DECIMAL(10,2),
    owner VARCHAR(100),
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
)
PRIMARY KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 1
PROPERTIES ("replication_num" = "1");

INSERT INTO orders (id, customer, amount, owner) VALUES
    (1, 'alice_customer_A', 100.00, 'analyst_alice'),
    (2, 'alice_customer_B', 120.50, 'analyst_alice'),
    (3, 'bob_customer_X',   250.00, 'analyst_bob'),
    (4, 'bob_customer_Y',   180.75, 'analyst_bob'),
    (5, 'carol_customer',    75.25, 'analyst_carol');

-- 5. Row-level security view: each user sees only rows matching their username
CREATE OR REPLACE VIEW my_orders AS
SELECT id, customer, amount, owner, created_at
FROM orders
WHERE owner = SUBSTRING_INDEX(REPLACE(CURRENT_USER(), CHAR(39), ''), '@', 1);

-- 6. Grants
GRANT SELECT ON ALL TABLES IN DATABASE information_schema TO USER 'metabase_svc'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE ON metabase_test.orders TO USER 'metabase_svc'@'%';
GRANT SELECT ON VIEW metabase_test.my_orders TO USER 'metabase_svc'@'%';

GRANT SELECT ON VIEW metabase_test.my_orders TO USER 'analyst_alice'@'%';
GRANT INSERT, UPDATE, DELETE ON metabase_test.orders TO USER 'analyst_alice'@'%';

GRANT SELECT ON VIEW metabase_test.my_orders TO USER 'analyst_bob'@'%';
GRANT INSERT, UPDATE, DELETE ON metabase_test.orders TO USER 'analyst_bob'@'%';
