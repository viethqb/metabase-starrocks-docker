# Metabase + StarRocks — Docker harness

A self-contained Docker setup that builds **Metabase from source** with a custom **StarRocks driver** baked in, plus a full StarRocks cluster, ready for end-to-end testing of:

- **Connection impersonation** — every query for an end user runs against StarRocks under a per-user identity via `EXECUTE AS … WITH NO REVERT`
- **Row-level security via DB views** — `WHERE owner = SUBSTRING_INDEX(CURRENT_USER(), '@', 1)`
- **Writeback** — Metabase Model Actions (INSERT / UPDATE / DELETE on Primary Key tables)

The Dockerfile clones upstream Metabase at any ref, copies in the StarRocks driver source, and produces a single image that already includes the plugin jar.

---

## Repo layout

```
.
├── Dockerfile                  # 2-stage build: clones Metabase, bakes the driver
├── compose.yml                 # Postgres app DB + StarRocks FE/BE + Metabase
├── apply-ee-bypass.sh          # Build-time patch script invoked by the Dockerfile
├── drivers-deps.edn            # modules/drivers/deps.edn with `:starrocks` registered
├── starrocks/                  # StarRocks driver source (copied into the Metabase clone)
│   ├── deps.edn
│   ├── resources/
│   │   └── metabase-plugin.yaml
│   └── src/metabase/driver/starrocks.clj
├── starrocks-test-setup.sql    # Bootstraps users, IMPERSONATE grants, RLS view, sample data
├── .env.example                # Override METABASE_REF / MB_EDITION
├── .dockerignore
└── .gitignore
```

---

## Architecture

```
┌──────────────────┐       Native query (Web UI / Actions / Subscription)
│ Browser / API    │
└────────┬─────────┘
         │ HTTPS
         ▼
┌─────────────────────────┐    Postgres app DB
│ Metabase (uberjar)      │◀──────────────────────┐
│   + :starrocks plugin   │                       │
└────────┬────────────────┘                       │
         │                                        │
         │ JDBC (MariaDB driver, port 9030)        │
         │  ┌─ Pooled connection (admin / sync)   │
         │  └─ Fresh non-pooled connection         │
         │       per impersonated user             │
         ▼                                        │
┌─────────────────────────┐                       │
│ StarRocks FE/BE         │                       │
│  ─ EXECUTE AS <user>    │                       │
│      WITH NO REVERT     │                       │
│  ─ View `my_orders`     │                       │
│      filters by         │                       │
│      CURRENT_USER()     │                       │
└─────────────────────────┘                       │
                                                  │
┌─────────────────────────┐                       │
│ Postgres (mb-postgres)  │───────────────────────┘
│  Metabase metadata      │
└─────────────────────────┘
```

### Why a custom driver?

StarRocks speaks the MySQL protocol, so the driver extends `:mysql`. The interesting parts:

| Method | Behaviour |
|---|---|
| `driver/database-supports? [:starrocks :connection-impersonation]` | `true` — opt into the EE impersonation pipeline |
| `driver.sql/default-database-role` | Returns the connecting user (service account) so the EE `set-role-if-supported!` path doesn't throw |
| `driver/set-role!` | Issues `EXECUTE AS '<user>' WITH NO REVERT`, but **first checks `SELECT CURRENT_USER()`**: if the role already matches the connection, it skips. (`EXECUTE AS` requires `IMPERSONATE` privilege on the target — including on yourself, which the service account does not have.) |
| `sql-jdbc.execute/do-with-connection-with-options` | When `*impersonation-role*` is bound, opens a **fresh non-pooled** JDBC connection from the raw spec, runs the query, then closes it. This is required because `EXECUTE AS … WITH NO REVERT` is **non-reversible per session** — a connection that has been impersonated cannot go back to the service identity, so it must never re-enter the c3p0 pool. Non-impersonated traffic continues to use the normal pooled path. |
| `sql-jdbc.sync/describe-fields-pre-process-xf` | Identity transducer override. The MySQL parent assumes the JDBC driver returns boolean columns as `0/1` integers and runs `pos?` over them; StarRocks returns real `Boolean` values, which would otherwise blow up `sync-fields` with `class java.lang.Boolean cannot be cast to class java.lang.Number`. |

See [`starrocks/src/metabase/driver/starrocks.clj`](starrocks/src/metabase/driver/starrocks.clj).

---

## Prerequisites

- Docker + Docker Compose v2
- ~8 GB RAM free (Metabase build peaks ~4 GB; StarRocks BE wants ≥2 GB)
- ~15 GB free disk for images and named volumes

The Metabase build is **only run when the Dockerfile changes** thanks to BuildKit caching. The first build takes 25–35 minutes (mostly Maven downloads + frontend bundling). Subsequent rebuilds are 5–10 minutes if only patches/driver source change.

---

## Quick start

```bash
git clone <this-repo> metabase-starrocks-docker
cd metabase-starrocks-docker

# Optional: pin a Metabase ref or switch to OSS edition
cp .env.example .env
$EDITOR .env

# 1. Build the Metabase image (long the first time)
docker compose build

# 2. Bring up Postgres + StarRocks + Metabase
docker compose up -d

# 3. Wait for everything to be healthy
docker compose ps
# → mb-postgres   (healthy)
# → mb-starrocks-fe   (healthy)
# → mb-starrocks-be   Up
# → mb-app   (healthy)   ← may take 30–90 s after start

# 4. Bootstrap StarRocks users, sample table, RLS view
docker cp starrocks-test-setup.sql mb-starrocks-fe:/tmp/setup.sql
docker exec mb-starrocks-fe \
    mysql -h127.0.0.1 -P9030 -uroot -e "source /tmp/setup.sql"

# 5. Open Metabase
open http://localhost:3002
```

> The compose file maps the container's port 3000 to host **3002** so it doesn't collide with another Metabase you might be running. Edit `compose.yml` to change.

---

## Configure Metabase (one-time UI setup)

### A. Add the StarRocks database

**Admin → Databases → Add database**

| Field | Value |
|---|---|
| Engine | **StarRocks** |
| Display name | `starrocks` |
| Host | `mb-starrocks-fe` |
| Port | `9030` |
| Database name | `metabase_test` |
| Username | `metabase_svc` |
| Password | `metabase_pw` |

The sync should finish in 30–60 s and discover both `orders` (table) and `my_orders` (view).

### B. Enable Model Actions

The toggle isn't always visible in the UI; the API is reliable. Grab your admin session cookie from DevTools (`Application → Cookies → metabase.SESSION`) and:

```bash
SESSION="<paste-cookie>"

DB_ID=$(curl -s "http://localhost:3002/api/database" \
            -H "Cookie: metabase.SESSION=$SESSION" \
        | python3 -c "import json,sys;print(next(d['id'] for d in json.load(sys.stdin)['data'] if d['engine']=='starrocks'))")

curl -s -X PUT "http://localhost:3002/api/database/$DB_ID" \
    -H "Content-Type: application/json" \
    -H "Cookie: metabase.SESSION=$SESSION" \
    -d '{"settings": {"database-enable-actions": true}}'
```

After enabling, hard-refresh any open dashboard to pick up the new "Add action button" affordance.

### C. Set up connection impersonation

1. **Admin → People → Groups → Create a group**: `Starrocks Impersonate`.
2. **Admin → Permissions → Groups → `Starrocks Impersonate` → row `starrocks`**:
   - **View data**: choose **Impersonated** → user attribute: `starrocks_user`.
   - **Create queries**: **Query builder and native**.
   - Save.
3. **Admin → Permissions → Groups → `All Users` → row `starrocks`**:
   - **View data**: **Blocked** (or **No self-service**) so impersonation isn't bypassed by membership in the default group.
   - Save.
4. **Admin → People → Invite people**:
   - `alice@test.com` — group `Starrocks Impersonate` — attribute `starrocks_user = analyst_alice`
   - `bob@test.com`   — group `Starrocks Impersonate` — attribute `starrocks_user = analyst_bob`

   Set passwords from the link Metabase shows (no SMTP configured by default).

---

## Test scenarios

### 1. Impersonation sanity check

Logged in as **alice**, open a Native question on `starrocks`:

```sql
SELECT CURRENT_USER();
```

- Expected: `'analyst_alice'@'%'`
- Same query as **bob**: `'analyst_bob'@'%'`
- Same query as **admin**: `'metabase_svc'@'%'` (admin bypasses impersonation)

### 2. Row-level security via DB view

```sql
SELECT * FROM metabase_test.my_orders;
```

`my_orders` is `SELECT … WHERE owner = SUBSTRING_INDEX(REPLACE(CURRENT_USER(), CHAR(39), ''), '@', 1)`.

| User | Rows seen |
|---|---|
| alice | rows where `owner = 'analyst_alice'` |
| bob   | rows where `owner = 'analyst_bob'` |
| admin (no impersonation) | nothing — `metabase_svc` is not an `owner` |

To make it more obvious, swap owners and re-test:

```sql
UPDATE metabase_test.orders SET owner = 'analyst_bob'   WHERE id IN (1,2);
UPDATE metabase_test.orders SET owner = 'analyst_alice' WHERE id IN (3,4);
```

Now alice sees ids 3, 4 and bob sees ids 1, 2 — same SQL, different results.

### 3. Connection-pool isolation

Run an impersonated query as alice, then immediately as admin. Admin must still get the unfiltered set — proves the impersonated `EXECUTE AS` connection never re-entered the c3p0 pool.

```bash
docker exec mb-starrocks-fe mysql -uroot -P9030 -e "SHOW PROCESSLIST"
```

You should see fresh, short-lived connections under each impersonated user, and the long-lived pool connections always under `metabase_svc`.

### 4. Writeback via Model Actions

1. **New → Model →** native query `SELECT * FROM metabase_test.orders` → save as `Orders model`.
2. Navigate to `http://localhost:3002/model/<id>-orders-model/detail/actions` (the link is hidden in the menu by upstream design — direct URL is intentional).
3. Click **Create basic actions** → Create / Update / Delete are auto-generated from the PK column `id`.
4. Run **Update** with `id=5`, `amount=999.99`. Verify the change persists in StarRocks.

Custom SQL action example (a single statement — Metabase actions are `executeUpdate()`):

```sql
UPDATE metabase_test.orders
SET amount = {{new_amount}}
WHERE id = {{order_id}}
  AND owner = SUBSTRING_INDEX(REPLACE(CURRENT_USER(), CHAR(39), ''), '@', 1);
```

The added `owner = …` clause means impersonated users can only edit their own rows — without the WHERE filter, all impersonated users with the right grants could edit any row.

### 5. Writeback under impersonation

Run the Update action as alice. The query executes as `analyst_alice` (granted `INSERT, UPDATE, DELETE` in the bootstrap SQL). A user whose impersonated target lacks DML grants will get `Access denied … on TABLE orders`.

---

## Operational notes

### Tearing down

```bash
docker compose down            # keep volumes (Postgres + StarRocks data)
docker compose down -v         # also wipe volumes
```

### Pinning a Metabase ref / changing the edition

Edit `.env`:

```env
METABASE_REPO=https://github.com/metabase/metabase.git
METABASE_REF=master            # or v1.58.0, or a SHA
MB_EDITION=ee                  # or oss
```

Then `docker compose build --no-cache metabase`.

### Iterating on the driver

Driver source lives in `starrocks/`. After editing:

```bash
docker compose build metabase   # cached up to the COPY step
docker compose up -d --force-recreate metabase
```

The Dockerfile's `COPY starrocks/ …` step invalidates the build cache only when those files change, so frontend / Maven layers stay cached.

---

## Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `Access denied … IMPERSONATE on USER metabase_svc` | Stale driver jar without the self-impersonation guard. Rebuild. |
| `class java.lang.Boolean cannot be cast to class java.lang.Number` during sync | MySQL parent's field-coercion transducer applied to StarRocks. The driver's `describe-fields-pre-process-xf` override fixes it — rebuild and re-sync. |
| Driver missing from the engines list | Plugin jar didn't land in `/plugins/`. Check `docker exec mb-app ls /plugins`. |
| Build fails: `Cannot run program "bun"` / `"uv"` | `bun` and `uv` must be installed in the builder stage; both are in the current Dockerfile. If you fork the Dockerfile, keep them. |
| Build fails: `Cannot invoke "java.lang.CharSequence.length()" because "s" is null` | Means `./bin/build.sh` hit an interactive prompt under TTY-less Docker — usually because an earlier step (Python deps, frontend) failed. Scroll up for the real error. |
| StarRocks BE shows `Alive: false` | Wait 30–60 s, then check `docker logs mb-starrocks-be`. Needs ≥ 2 GB RAM. |
| Actions UI not visible | Hard-refresh the browser. Confirm `database-enable-actions=true` via the API in step B. |
| 414 URI Too Long when sharing dashboards with long filters | URL encoding limit; use POST-based dashboard subscriptions instead of bookmarked links. |
| `bun install` / Maven download takes forever | Network. Re-run `docker compose build` — BuildKit will resume from cache. |

---

## Limitations & known caveats

- **Single-statement actions**: Metabase actions execute via JDBC `executeUpdate()`. Multi-statement SQL (`UPDATE …; INSERT …;`) is not supported. Workaround: separate actions, or pack logic into a single statement (CTEs, etc.). StarRocks doesn't support stored procedures.
- **`actions/data-editing` requires Primary Key tables** in StarRocks. Duplicate-Key / Aggregate-Key tables have no PK metadata; Metabase can't generate row-level CRUD for them.
- **Per-user dashboard cards**: Metabase has no built-in mechanism to show/hide cards in one dashboard per user. Use collection-level permissions or duplicate dashboards.
- **Per-user table columns**: Metabase has no native column-level permissions. Use a DB view with `CASE WHEN CURRENT_USER() … THEN col ELSE NULL END` to mask values, or sandboxing for a different SELECT per group.

---

## Service / credential cheat sheet

| Container | Host port | Credentials |
|---|---|---|
| `mb-app` (Metabase) | http://localhost:3002 | first user becomes admin |
| `mb-postgres` (Metabase app DB) | not exposed | `metabase` / `metabase_secret` |
| `mb-starrocks-fe` (MySQL protocol) | `localhost:9030` | `root` (no password) |
| `mb-starrocks-fe` (HTTP) | http://localhost:8030 | — |
| `mb-starrocks-be` | not exposed | — |
| StarRocks service account | — | `metabase_svc` / `metabase_pw` |
| Impersonation targets | — | `analyst_alice` / `alice_pw`, `analyst_bob` / `bob_pw` |
