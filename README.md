# wlwl-backend (Java)

Java 21 / Spring Boot 3.3 port of the `wlwl` PHP backend. The goal is **1:1
behavioural parity**, not a redesign: the existing Vue frontend must keep working
against this service unchanged, which means the same paths, the same JSON shapes,
the same status codes, and the same authentication quirks.

## Status

| | |
|---|---|
| Modules ported | **30 of 33** — plus 2 dropped by ruling (`signin`, `messages`); `drill` is the only module still in progress (all of it ported except `mod=ACTION`'s `MORE_ACTIONS` branch, see `docs/PORTING_NOTES.md` §21 and §29) |
| Gateway / RPC core | done and tested |
| PHP type-faithful row mapping | done and tested |
| PHP-faithful nested array mapping | done and tested |
| Auth (`restsession` affected-rows gate) | done and tested |
| Login + MySQL `PASSWORD()` hashing | done and tested |
| Tests | **239 passing** (`mvn verify`, 31 test classes, verified 2026-10-28) |

Not fully ported yet:

| module | status |
|---|---|
| `drill` (5,617 LOC; 2,798 live) | **IN PROGRESS** — 17 of 18 files done, including `commonLib` (22/22), `init`, `environment`, `timeout`, `reset`, `updatename`, `competency`, `bossreview`, `post`. Only `mod=ACTION`'s `MORE_ACTIONS` branch remains: passes 1–2 (`applyRecruits`, `applySwapTask`) are written and unit-tested but **not yet wired** into the dispatch; passes 3–5 are unported and answer 501 with the branch name. `mod=ACTION`'s `SELECT_STYLE` branch is done. Data blockers are cleared (two real drill dumps). |
| `signin` (144) | **DROPPED by ruling** — dead: no `wxuser`/`studentlist`/`tasks`, and 0 client call sites |
| `messages` (51) | **DROPPED by ruling** — dead; risk accepted and logged (see ledger risk register R1–R4) |

See `../wlwl_parity_ledger.md`.

### Remaining work, as of 2026-10-28

1. **`MORE_ACTIONS` passes 3–5** (`drill/action.php:179-738`) — the only unported
   behaviour left. Then wire passes 1–2 into the dispatch (they exist and are unit-tested
   but are currently reached only from tests).
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
| `db/tools/test-migrate.sh` | CI: 9 assertions on `migrate.sh`, needs no database |

Two deliberate differences from the raw dump: its `DROP TABLE IF EXISTS` statements are
removed (they would destroy a live database — `check-sql.py` fails the build if one
reappears), and view `DEFINER=`root`@`localhost`` is stripped. Everything else, including
`AUTO_INCREMENT` counters and column order, is verbatim — column order matters because the
backend has ~52 `SELECT *` paths.

**For a production cutover, schema comes from `db/schema.sql` and data comes from the
legacy database** — `seed-dev.sql` is fixtures:

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
