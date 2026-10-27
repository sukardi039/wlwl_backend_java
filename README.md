# wlwl-backend (Java)

Java 21 / Spring Boot 3.3 port of the `wlwl` PHP backend. The goal is **1:1
behavioural parity**, not a redesign: the existing Vue frontend must keep working
against this service unchanged, which means the same paths, the same JSON shapes,
the same status codes, and the same authentication quirks.

## Status

| | |
|---|---|
| Modules ported | **4 of 31** (`staffsignin`, `basicinformation`, `baseinfo`, `staff`) |
| Gateway / RPC core | done and tested |
| PHP type-faithful row mapping | done and tested |
| PHP-faithful nested array mapping | done and tested |
| Auth (`restsession` affected-rows gate) | done and tested |
| Login + MySQL `PASSWORD()` hashing | done and tested |
| Tests | **65 passing** (`mvn test`) |

Not yet ported: `drill` (5,617 LOC — 61% of the backend, the real work),
`backenduser`, `drilloverview`, and 26 others.
See `../wlwl_parity_ledger.md`.

## Build and run

Requires JDK 21 and Maven. A local toolchain was used during development at
`/opt/data/toolchain` — `. /opt/data/toolchain/env.sh` if you are on that box.

```bash
mvn test          # 10 parity tests, H2 in-memory, no database needed
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
modules/
  StaffsigninModule.java  port of staffsignin/ (login, session, GETAUTH/CHKAUTH)
  BasicinformationModule.java  port of basicinformation/post.php (18 sections)
  BaseinfoModule.java     port of baseinfo/post.php + put.php
  StaffModule.java        port of staff/post.php
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
