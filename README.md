# wlwl-backend (Java)

Java 21 / Spring Boot 3.3 port of the `wlwl` PHP backend. The goal is **1:1
behavioural parity**, not a redesign: the existing Vue frontend must keep working
against this service unchanged, which means the same paths, the same JSON shapes,
the same status codes, and the same authentication quirks.

## Status

| | |
|---|---|
| Modules ported | **32 of 32 live modules** — 2 more dropped by ruling (`signin`, `messages`). `drill/` is complete: 18 of 18 files, both `mod=ACTION` branches |
| Gateway / RPC core | done and tested |
| PHP type-faithful row mapping | done and tested |
| PHP-faithful nested array mapping | done and tested |
| Auth (`restsession` affected-rows gate) | done and tested |
| Login + MySQL `PASSWORD()` hashing | done and tested |
| Tests | **265 passing** (`mvn verify`, 38 test classes, verified 2026-10-29) |

The largest module, and the only one that needed a decomposition plan:

| module | status |
|---|---|
| `drill` (5,617 LOC; 2,798 live) | **PORTED + tested** — all 18 files, `commonLib` 22/22, and all three `mod=ACTION` actions the client sends: `INTRODUCTION` (the PHP has no case for it — it falls through to the shared tail, see `PORTING_NOTES` §34), `SELECT_STYLE`, and `MORE_ACTIONS` incl. the evaluate path. The quirks reproduced there are listed in `docs/PORTING_NOTES.md` §32; read it before touching that branch. |
| `signin` (144) | **DROPPED by ruling** — dead: no `wxuser`/`studentlist`/`tasks`, and 0 client call sites |
| `messages` (51) | **DROPPED by ruling** — dead; risk accepted and logged (see ledger risk register R1–R4) |

See `../wlwl_parity_ledger.md`.

### Remaining work, as of 2026-10-28

1. ~~**`MORE_ACTIONS` passes 3–5**~~ — **done**. Every backend module is now ported. What is
   left is cutover work, not porting: see items 2–4.
2. **Rulings pending** on the four dead/superseded PHP files listed in the ledger under
   "Rulings needed" (`basicinformation/x_post.php`, `staffsignin/post_xxx.php`,
   `backenduser/post_saved_20231209.php`, and whether to keep `staffsignin/put.php`'s
   500-for-parity branch).
3. **Packaging** — done: `.github/workflows/ci.yml` builds, verifies and publishes the
   jar from the repo (tag `v*` → GitHub Release asset); `db/` provides the schema, a dev
   seed and `migrate.sh`, with both SQL files and the script self-tested in CI.
4. **Frontend endpoints with no backend at all** — `idx_baseinfo`, `idx_baseclass`,
   `idx_staff`, `idx_profileclass`, `student` (15 call sites) 404 in PHP production today;
   the port reproduces that 404. Product decision whether those features stay missing.

## Build and run

Requires JDK 21 and Maven. A local toolchain was used during development at
`/opt/data/toolchain` — `. /opt/data/toolchain/env.sh` if you are on that box.

```bash
mvn test          # full parity suite, H2 in-memory, no database needed
mvn package       # produces target/wlwl-backend-0.1.0-SNAPSHOT.jar
```

Run against the real database:

```bash
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=wlwl DB_USER=root DB_PASSWORD=...
java -jar target/wlwl-backend-0.1.0-SNAPSHOT.jar
```

Health check (any unknown module returns 404 once the server is up):

```bash
curl -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/backend/probe/
```

## Build and deploy via the GitHub repo

`.github/workflows/ci.yml` is the delivery path — nothing needs to be built on the
target server except the JRE:

| trigger | what happens |
|---|---|
| push to any branch / PR | `mvn -B verify` on JDK 21 (Temurin) with Maven cache; the jar is uploaded as the `wlwl-backend-jar` artifact (30 days) |
| any failure | `surefire-reports/` is uploaded as an artifact so the failing parity test is readable in the UI |
| push a tag `v*` | the same verified jar is attached to a generated GitHub Release, so the server pulls a release asset instead of compiling |
| every push (after `verify`) | the `docker` job builds the image and smoke-tests it: container must run as UID 10001, reach `healthy` with **no database**, and still answer `POST /backend/drill` without a `restkey` with an empty `200` |

To ship a build:

```bash
# 1. bump the version in pom.xml if this is a real release (it ships as
#    wlwl-backend-<version>.jar, currently 0.1.0-SNAPSHOT)
# 2. tag and push
git tag v0.1.0 && git push origin v0.1.0
# 3. on the server: download the asset from the Release page, then run it with
export DB_HOST=... DB_PORT=3306 DB_NAME=wlwl DB_USER=... DB_PASSWORD=...
java -jar wlwl-backend-0.1.0-SNAPSHOT.jar
```

The workflow needs no secrets: the parity suite runs entirely on in-memory H2, and
database credentials are only ever supplied at runtime through the environment. The
`contents: write` permission exists solely to create the Release on a tag.

## Docker

The container is a **packaging option, not a change to the contract**: it sets only
the environment variables `application.yml` already reads (`DB_*`, `PORT`,
`WLWL_UPLOAD_DIR`, `TZ`) and adds no endpoint, header or path. The same image is
therefore cutover-equivalent to pulling the jar from a Release.

```bash
# image only
docker build -t wlwl-backend:0.1.0 .

# image build that also gates on the parity suite (skipped by default; CI runs it)
docker build -t wlwl-backend:0.1.0 --build-arg MVN_ARGS="-B verify" .

# run it against a database
docker run -d --name wlwl -p 8080:8080 \
  -e DB_HOST=host.docker.internal -e DB_PORT=3306 -e DB_NAME=wlwl \
  -e DB_USER=wlwl -e DB_PASSWORD=*** \
  -e WLWL_UPLOAD_DIR=/data/uploads \
  -v wlwl-uploads:/data/uploads \
  wlwl-backend:0.1.0
```

| file | role |
|---|---|
| `Dockerfile` | two stages: `maven:3.9.9-eclipse-temurin-21` builds, `eclipse-temurin:21-jre-jammy` runs the fat jar; `pom.xml` is copied before `src/` so dependency downloads stay cached across source edits |
| `.dockerignore` | keeps `target/`, `db/`, `docs/` and `.git/` out of the build context |
| `docker-compose.yml` | **local/dev only**: the app plus `mysql:8.0` pinned to `utf8mb3`, with `db/schema.sql` + `db/seed-dev.sql` as init scripts |

Things that are deliberate, not defaults worth "fixing":

- **`TZ=Asia/Shanghai`.** The PHP ran on a Windows/IIS host in Asia/Shanghai and
  writes local-time `DATETIME`s. The JVM reads `TZ`, so this keeps date results
  identical to production. Changing it changes stored times.
- **`WLWL_UPLOAD_DIR=/data/uploads`.** In production `params.fileLocation` holds a
  *Windows* path, so it is useless on Linux; this override wins before the DB lookup.
  Mount a volume there or uploads are lost when the container is recreated.
- **Runs as UID 10001, not root.** Mount the upload volume with matching ownership
  (`--user 10001` is already the default inside the image).
- **`HEALTHCHECK` probes an unknown module and expects `404`.** That path is answered
  by the dispatcher *before any database access*, so it proves the HTTP stack and
  gateway are up without consuming a connection or polluting logs. **It is a
  liveness probe** — the container reports `healthy` even when MySQL is down. No
  Spring Actuator dependency was added on purpose: it would create a route surface
  (`/actuator/**`) that the PHP never had.
- **Tests are not run in the image build.** `mvn verify` (254 tests) runs in the CI
  `verify` job; duplicating it in every layer rebuild is wasted time. Use
  `--build-arg MVN_ARGS="-B verify"` when you want the image to be the gate.

### Why the app does not create the schema itself

Spring Boot only auto-creates tables in a **JPA/Hibernate** app (`ddl-auto`) or via
`spring.sql.init.mode` + a classpath `schema.sql`. This port uses
`spring-boot-starter-jdbc` with hand-written SQL, so there are no entities to derive
a schema from, and `db/schema.sql` deliberately lives outside the jar (it is the
production dump, not application code).

Turning on `spring.sql.init.mode=always` was measured, not assumed:

| | database unreachable at boot |
|---|---|
| current | `Started WlwlApplication in 4.899 seconds` — server up, requests get the PHP-faithful 500 |
| with `spring.sql.init.mode=always` | `Failed to execute database script` → `Connection refused` → **startup aborts**, exit 1 |

So auto-init would make liveness depend on MySQL (it would also fail the CI smoke
test, which runs the container with no database), and it would put `CREATE TABLE`
statements against a live production schema on every boot. Parity is the point here:
the PHP never created tables either.

Use the `db-init` one-shot service instead when a database exists but has no tables
— it applies `db/schema.sql` once, on demand, and never runs during `up`:

```bash
docker compose --profile init run --rm db-init
docker compose exec db mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wlwl -e 'show tables' | wc -l
```

`schema.sql` uses `CREATE TABLE IF NOT EXISTS` / `CREATE OR REPLACE VIEW` and contains
no `DROP`, so it is safe to re-run and safe on a database that already holds rows.

### When a request 500s, read the last `Caused by`

The port answers a failed query with a 500 and a stack trace, which is what the PHP
did when `mysqli_query` returned false (IIS surfaced it as 500). That is intended, so
the diagnosis is always in the final `Caused by:` line:

| last `Caused by` | meaning |
|---|---|
| `java.net.UnknownHostException: mysql` | DNS only — no such host *visible from this container*. Not credentials, not a firewall. The name must match the database service (`db` here, also aliased `mysql`) and both containers must share one user-defined network. A container started with plain `docker run` joins the default `bridge` network, where **service names never resolve** — create a network and attach both. |
| `CommunicationsException: Communications link failure` / `Connection refused` | The name resolved but nothing is listening: wrong port, MySQL still starting, or it only listens on a socket. `docker compose logs db` should show `ready for connections`. |
| `Access denied for user 'x'@'y'` | Credentials or database name. `MYSQL_USER`/`MYSQL_PASSWORD` only take effect when the `dbdata` volume is **first** created; changing them afterwards does nothing to an existing volume. |
| `Unknown database 'wlwl'` | Schema never applied — check the init scripts ran (they only run on an empty volume; `docker compose down -v` to redo). |
| `Table 'wlwl.backenduser' doesn't exist` | The database exists but holds no tables. `MYSQL_DATABASE` creates only the empty schema; the tables come from `db/schema.sql`, and `docker-entrypoint-initdb.d` scripts run **once**, when the data directory is brand new. Apply it now — `db/schema.sql` is additive (no `DROP`), so this is safe on a populated database: `docker compose exec -T db mysql -uroot -p"$MYSQL_ROOT_PASSWORD" wlwl < db/schema.sql`, then confirm with `./db/migrate.sh --check` (must print `tables: 40`, `views: 5`, `OK`). |

Diagnostics that distinguish these in one step:

```bash
docker compose exec app getent hosts db          # empty output = DNS problem
docker compose exec app curl -sS -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:8080/backend/__probe__         # 404 = HTTP stack is fine
docker compose logs db | tail -5                  # is MySQL actually up?
```

Timestamps in the log carrying `+08:00` are expected — that is `TZ=Asia/Shanghai`
doing its job for date parity, not a symptom.

## `registration.json` — the login screen's identity file

The Vue client does `fetch("backend/registration.json")` in `Login.vue`'s `created()`,
before authentication, and sets `document.title = data.company`. It is **not** an RPC
module and **not** an endpoint the PHP had in source: `.gitignore` in `wlwl_backend`
lists `registration.json` on line 2, so each IIS deployment supplied its own copy
(company name, registration numbers, regulator links). That is why the port shipped
without it — there was no file in the repo to copy.

The Java app serves `GET /backend/registration.json` from, in order:

| order | source |
|---|---|
| 1 | `WLWL_REGISTRATION_FILE` — the deployment's own file, the equivalent of the gitignored one |
| 2 | bundled `src/main/resources/registration.json` — a **placeholder** (`{"company":"wlwl", ...}`) |

```bash
docker run ... -e WLWL_REGISTRATION_FILE=/data/registration.json \
  -v /srv/wlwl/registration.json:/data/registration.json:ro wlwl-backend:0.1.0
```

The seven keys the client reads, all required:

```json
{
  "company": "PT Example",
  "reg":     "No. 12345/ABC",
  "url":     "https://registrar.example/company",
  "ida":     "IDA-12345",
  "polreg":  "/images/polreg.png",
  "polurl":  "https://example/privacy",
  "pol":     "Privacy Policy"
}
```

Rules, and why:

- **A configured-but-missing file answers 404** — never the placeholder. A live system
  advertising the wrong company name and registration numbers is worse than a blank
  footer, and 404 is exactly what IIS did, so the client's `if (res.status === 200)`
  guard behaves identically.
- **Bytes are passed through unchanged**, so the deployed file's key order survives. The
  file is not re-serialised and not validated at startup.
- **It needs no `restkey`**, like the other pre-auth responses — the login page fetches it
  before anyone signs in.
- The bundled placeholder **must be replaced in production**; it exists so a fresh
  checkout answers 200 with a well-formed body instead of 404.

## Database: schema and seed

`db/` contains the database half of the deliverable. The schema is **generated from the
production `mysqldump --no-data`** (MySQL 8.0.40, 40 tables + 5 views), never retyped.

```bash
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=wlwl DB_USER=root DB_PASSWORD=***

./db/migrate.sh --schema        # create missing tables/views — safe, repeatable
./db/migrate.sh --check         # report 40 tables / 5 views, change nothing
./db/migrate.sh --seed --dev    # load synthetic DEV rows (refused if the DB has data)
./db/migrate.sh --dry-run       # print what would happen, touch nothing
```

| file | role |
|---|---|
| `db/schema.sql` | generated: 40 tables, 5 views, 363 columns, 34 FKs, `utf8mb3` |
| `db/seed-dev.sql` | synthetic dev rows from the H2 fixture — **never for production** |
| `db/migrate.sh` | applies them; password goes via a 0600 defaults-file, not `argv` |
| `db/tools/from-dump.py` | regenerates `schema.sql` from a dump |
| `db/tools/make-seed.py` | regenerates the seed and checks every column against the schema |
| `db/tools/check-sql.py` | CI: both files parse as MySQL, counts, no `DROP` in schema |
| `db/tools/check-sql-usage.py` | CI: every table/column the JAVA queries mention exists in `schema.sql` |
| `db/tools/test-migrate.sh` | CI: 9 assertions on `migrate.sh`, needs no database |
| `db/tools/check-schema-parity.py` | CI: proves `schema.sql` is structurally identical to the original database |
| `db/tools/schema_model.py` | the DDL parser that check uses (stdlib only, no server) |
| `db/reference/wlwl_schema_real.md` | the condensed `--no-data` dump summary, used as the tier-B reference |

Two deliberate differences from the raw dump: its `DROP TABLE IF EXISTS` statements are
removed (they would destroy a live database — `check-sql.py` fails the build if one
reappears), and view `DEFINER=`root`@`localhost`` is stripped. Everything else, including
`AUTO_INCREMENT` counters and column order, is verbatim — column order matters because the
backend has ~52 `SELECT *` paths.

The port's correctness argument is 1:1 parity, and the database is part of the contract —
column *order* alone is load-bearing, because ~150 `SELECT *` paths turn column order into
JSON key order. `db/tools/check-schema-parity.py` asserts that `schema.sql` is structurally
identical to the original database, at two strengths:

```bash
# tier A (authoritative): exact diff against a real dump of the original database
mysqldump --no-data --skip-dump-date wlwl > /tmp/prod-schema.sql   # on the prod host
./db/tools/check-schema-parity.py --dump /tmp/prod-schema.sql

# tier B (what CI can run): diff against the condensed dump summary in db/reference/
./db/tools/check-schema-parity.py --allow-unverified
./db/tools/check-schema-parity.py --self-test     # proves the checker catches drift
```

Tier A compares every table, column (in order), type, nullability, default, `AUTO_INCREMENT`,
key, foreign key and rule, table option, and view body. Tier B asserts what the summary
actually states and prints anything it does not state as **UNVERIFIED** rather than assuming
it matches.

**In production this file is a bootstrap, not a migration.** The legacy MySQL instance
already has these 40 tables, so the normal cutover is to point the new jar at it and run
nothing from `db/`. `schema.sql` is only executed when standing up a *fresh, empty*
instance; because every statement is `CREATE TABLE IF NOT EXISTS` / `CREATE OR REPLACE
VIEW`, running it against the legacy database is a no-op rather than a rewrite. Data
always comes from the legacy database — `seed-dev.sql` is fixtures:

```bash
mysqldump --no-create-info --complete-insert wlwl > wlwl-data.sql
mysql wlwl < wlwl-data.sql
```

## The contract being preserved

Every call is `POST /backend/{module}/` with this body:

```json
{ "restkey": "...", "classes": ..., "mod": ..., "method": "POST",
  "limit": ..., "qdata": {...}, "package": {...} }
```

Five behaviours that are load-bearing and must never be "cleaned up":

1. **`method` in the body overrides the HTTP verb.**
2. **The raw query string is business data.** `POST /backend/drill/?U123` means
   userId `U123`. It is not a pager.
3. **Query string presence switches response shape**: no query string → array of
   rows; query string present → a single object (the last row).
4. **Invalid `restkey` returns an empty `200`**, not 401/403.
5. **Empty result sets return `204`**, not `200 []` — the client checks
   `res.status === 200` and treats everything else as "no data".

## Layout

```
rpc/
  BackendGateway.java     the single front door (port of etc/restdx.inc)
  RpcEnvelope.java        request envelope + PHP truthiness helpers
  RpcContext.java         response helpers: json / noContent / statusJson
  ModuleHandler.java      one per PHP module directory
  ModuleRegistry.java     module segment -> handler
  RpcEnvelopeReader.java  repeated field coercions
session/
  RestSessionService.java auth by UPDATE-affected-rows
db/
  PhpRows.java            MySQL/JDBC -> PHP-compatible JSON values
  PhpArray.java           PHP array semantics (key coercion, array-vs-object)
  PhpUniqid.java          uniqid(): 13 lowercase hex
  MysqlPassword.java      MySQL 4.1 PASSWORD() hash, computed in Java
service/
  LicenseAssignment.java  seat allocation shared with registerdemo/
  ProfileSeeder.java      the 3-way profile combination seeding
  ProfileExpansion.java   code -> display name / display key maps
modules/
  LicenseModule / PackageremoveModule / ControldrillModule / RegisterdemoModule
  SimulatorModule.java     what-if stepper (rand-based init, deterministic deltas)
  ConsoleuserModule.java   raw latest-step feed
  AbstractCatalogueModule.java  shared list/upsert/re-read shape
  EnvironmentalModule / TemplateModule / ProductModule / StyleModule / TaskModule
  AgentModule / LicensebatchModule / PackageModule
  ActionModule.java        baseinfo 'actions' + 3 lookups
  TaskefficiencyModule.java
  ProfileclassModule.java  dynamic table name, identifier-guarded
  ProfileimpactModule.java / ProfileaffectModule.java  (shared post.php)
  ProfilesensModule.java   port of profilesens/ (Exp + Prf)
  ModelModule.java         port of model/ (get/post/put)
  DrilloverviewModule.java port of drilloverview/ (scoreboard + ranking)
  DrilleffectiveModule.java port of drilleffective/ (per-action percentages)
  DrillcapableModule.java  port of drillcapable/ (competency scoreboard)
  BackenduserModule.java  port of backenduser/ (list users, create/update)
  StaffsigninModule.java  port of staffsignin/ (login, session, GETAUTH/CHKAUTH)
  BasicinformationModule.java  port of basicinformation/post.php (18 sections)
  BaseinfoModule.java     port of baseinfo/post.php + put.php
  StaffModule.java        port of staff/post.php
  DrillModule.java        port of drill/ — mod dispatch: TIMEOUT, RESET, UPDATENAME, INIT
  DrillCommon.java        (service/) drill/commonLib.php helpers, 12 of 22 so far
  DrillInit.java          (service/) drill/init.php — builds the drill state document
  DrillEnvironment.java   (service/) drill/environment.php — fires the day's event
  DrillPost.java          (service/) drill/post.php — POST verb: control + runs home screen
  BossReview.java         (service/) drill/bossreview.php — the boss's reaction to a step
```

## Adding a module

Copy the shape of `BaseinfoModule`:

1. `@Component`, implement `ModuleHandler`, return the directory name from
   `module()`.
2. Port the PHP file body-by-body into `onGet` / `onPost` / `onPut` / `onDelete`.
3. Use `PhpRows.query(...)` so JSON types stay PHP-shaped.
4. Use `PhpRows.arrayOrLastRow(rows, ctx.queryStringLength())` for the shape.
5. Leave unimplemented verbs empty — that reproduces the "no get.php" case.
6. Add a test case to `BackendGatewayParityTest` or a module-specific test class.

Modules that build a nested aggregate (rather than returning rows) must build it
with `PhpArray`, not `Map`/`List`: PHP decides array-vs-object from the key type,
and `basicinformation` returns ~300 keys of nested PHP arrays.

Read `docs/PORTING_NOTES.md` before writing any module. It lists the divergences
that were chosen deliberately and the traps that will silently break parity.
