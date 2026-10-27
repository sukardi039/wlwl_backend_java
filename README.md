# wlwl-backend (Java)

Java 21 / Spring Boot 3.3 port of the `wlwl` PHP backend. The goal is **1:1
behavioural parity**, not a redesign: the existing Vue frontend must keep working
against this service unchanged, which means the same paths, the same JSON shapes,
the same status codes, and the same authentication quirks.

## Status

| | |
|---|---|
| Modules ported | **30 of 33** — plus 2 dropped by ruling (`signin`, `messages`), 1 in progress (`drill`: 5 of 6 mods + `post.php` outstanding, see `docs/PORTING_NOTES.md` §21) |
| Gateway / RPC core | done and tested |
| PHP type-faithful row mapping | done and tested |
| PHP-faithful nested array mapping | done and tested |
| Auth (`restsession` affected-rows gate) | done and tested |
| Login + MySQL `PASSWORD()` hashing | done and tested |
| Tests | **205 passing** (`mvn test`, 24 test classes) |

Not yet ported, and why:

| module | status |
|---|---|
| `drill` (5,617 LOC) | **blocked** — needs real `drilldetail.content` sample rows |
| `signin` (144) | **DROPPED by ruling** — dead: no `wxuser`/`studentlist`/`tasks`, and 0 client call sites |
| `messages` (51) | **DROPPED by ruling** — dead; risk accepted and logged (see ledger risk register R1–R4) |
See `../wlwl_parity_ledger.md`.

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
