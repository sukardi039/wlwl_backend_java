# Porting notes

Decisions, deliberate divergences, and the traps that break parity silently.

---

## 1. Architecture decision: Spring JDBC, not JPA/Hibernate

The schema analysis recommended JPA. **That recommendation is withdrawn**, and
this is why:

The PHP builds SQL strings by hand and returns raw result rows straight to
`json_encode`. There are no domain objects, no entity graph, no identity map.
About 270 queries concatenate values into SQL text, and roughly 52 code paths
depend on `SELECT *` returning the table's column set in table order.

An ORM buys nothing here and costs a lot:

- `action.1In4` starts with a digit — every entity would need a renamed field.
- `params.key` / `params_int.key` are reserved words needing escaped identifiers.
- `simulator(sessionId, seqNumber)` composite PK, `competency` shared-PK 1:1,
  `product` with an auto-increment on a non-PK column — three different awkward
  mappings for zero benefit.
- The five views are read-only and one of them (`impactprofile_view`) joins
  `profileclass` three times with the equality predicates in `WHERE`.
- Hand-written SQL maps 1:1 onto the PHP, which makes review and diffing
  straightforward. Entities would *obscure* the parity.

So: `JdbcTemplate` + `PhpRows`. Table-per-table mapping work drops from weeks to
nearly nothing, and the port stays mechanically comparable to the original.

## 2. Deliberate divergences from the PHP

Every one of these changes behaviour on purpose. Confirm each is acceptable.

| # | PHP behaviour | Java behaviour | Why |
|---|---|---|---|
| 1 | `$restkey` interpolated into SQL → SQL injection | bound parameter | non-negotiable |
| 2 | all other values interpolated → SQL injection | bound parameters | non-negotiable |
| 3 | `limit` interpolated raw into `LIMIT` | parsed as `Integer`, non-numeric ignored | a non-numeric `limit` currently 500s |
| 4 | `include "get.php"` missing → PHP warning in output | silent empty 200 | warnings were never valid JSON anyway |
| 5 | fatal SQL error → 500 with an HTML error page | 500 with empty body | body is never parsed by the client on non-200 |
| 6 | `Content-type: text/html` on `echo` responses | `text/plain;charset=UTF-8` | identical bytes either way |
| 7 | `json_encode` escapes `/` and unicode as `\uXXXX` | Jackson does not | both decode to the same value; only a byte-diff harness would notice |
| 8 | `password()` SQL function for login | not used — see §4 | removed in MySQL 8.0 |

Divergences 1–3 mean malicious/adversarial input behaves differently (it fails
rather than executing). Normal input is unaffected.

## 3. Parity traps — read before porting any module

- **`$_SERVER['QUERY_STRING']` is the business key in some modules.**
  `drill/post.php` does `where userId = '".$_SERVER['QUERY_STRING']."'`. Use
  `ctx.queryString()` — raw, undecoded, exactly like PHP. Never use
  `@RequestParam`.
- **`strlen($QUERY_STRING)` switches the response between array and object.**
  Use `PhpRows.arrayOrLastRow(rows, ctx.queryStringLength())`. The object case
  keeps the **last** row, not the first.
- **`http_response_code(204)` on empty results, everywhere.** 61 call sites.
  `ctx.noContent()`.
- **`mod` is dual-purpose**: an action selector (`drill/put.php` switches on
  `INIT`/`ACTION`/`ENVIRONMENT`/`RESET`/`TIMEOUT`/`UPDATENAME`) and a plain
  WHERE value (`baseinfo/post.php` uses it as `baseClass`). Read each module.
- **NULL becomes `''` in string concatenation.** PHP turns
  `'... = '".$null."'` into `= ''`, which can match rows. Binding SQL `NULL`
  matches nothing. Always coerce with `textOrEmpty()` before binding.
- **DECIMAL comes back as a JSON string.** `PhpRows` handles this; do not read
  raw `ResultSet` values in a module.
- **Booleans are `int` columns.** Never map to `boolean`; JSON must stay `0`/`1`.
- **`restsession.usedOn` is `varchar(100)`** holding `2026-10-26 15:47:01.4231`.
  Not a datetime type. Do not "fix" it.
- **Synthetic column injection.** `$row['key'] = $row['name']` and
  `$row['skillsExp'] = ...` overwrite real columns of the same name if they ever
  appear. Reproduce, do not "fix".
- **`uniqid()` is used for primary keys** (24 call sites). It yields 13 hex chars.
  Any Java replacement must produce unique string ids; do not switch to UUIDs
  without checking width-sensitive code and the FK lengths (`varchar(100)` is
  fine, but ordering assumptions are not).

## 4. Authentication and the MySQL 8 `PASSWORD()` blocker

The PHP authenticates logins with:

```php
where b.loginName = '...' and b.loginPassword = password('...')
```

`PASSWORD()` was **removed in MySQL 8.0**. On the 8.0.40 instance that produced
the schema dump, these statements fail. See `../wlwl_schema_analysis.md` §1.

Port plan:

1. Add a `PasswordEncoder` that verifies MySQL 4.1 hashes:
   `"*" + hex(sha1(sha1(password))).toUpperCase()`.
2. On successful login, re-hash with BCrypt and update the row.
3. Keep both formats accepted until all rows are migrated.
4. Never call `PASSWORD()` from JDBC — it does not exist.

`company.commonPW` defaults to plaintext `'123456'` and resets every
non-instructor password in a company. That stays until someone decides otherwise.

## 5. Database connection settings that matter

- `connectionCollation=utf8mb3_general_ci` — every table is `CHARSET=utf8mb3`.
  Connecting as utf8mb4 changes how 4-byte characters are handled versus the PHP
  (which ran with `SET NAMES utf8`).
- `serverTimezone` — the PHP used the server's local time (`date()`), and stored
  local-time strings in `datetime` columns. Set this to the production server's
  zone or every timestamp shifts.
- `useSSL=false` and `allowPublicKeyRetrieval=true` are for local/dev only.

## 6. Testing strategy

- `BackendGatewayParityTest` — 8 tests, the RPC contract.
- `PhpRowsTypeParityTest` — 2 tests, JSON value types and column order.
- Both run on H2 in MySQL mode via `application-test.yml`; no database required.
- H2 lowercases unquoted identifiers, so tests address columns
  case-insensitively via `PhpRows.get(row, "camelCaseName")`. Production MySQL
  preserves declared casing.

Next: the golden-master harness. Record real request/response pairs from the PHP
app and replay them against this service, diffing JSON. That is the only way to
prove parity for the ~52 query-string-dependent code paths that no unit test
will cover.

## 7. Nested aggregates: PHP decides array-vs-object from the KEY TYPE

Flat modules (baseinfo, staff) return `List<Map<String,Object>>` and Jackson emits
the right thing. `basicinformation` does not — it returns ~300 keys of nested PHP
arrays, and PHP's `json_encode` picks array vs object from the keys:

```php
array('a','b')           -> ["a","b"]
array('x'=>1)            -> {"x":1}
array(0=>'a',1=>'b')     -> ["a","b"]          // sequential from 0
array(1=>'a',2=>'b')     -> {"1":"a","2":"b"}  // not sequential from 0
array()                  -> []
```

PHP also coerces numeric-string keys to ints on insertion: `'123'` becomes int
`123`, while `'01'`, `'1.0'`, `'+5'` and `' 5'` stay strings. Building these
aggregates with a `Map<String,Object>` therefore produces valid JSON with the
WRONG structure, and the Vue client fails silently — `Array.isArray()` returns
false and `.map()` throws somewhere unrelated.

So: build aggregates with `db/PhpArray`, which normalises keys on every
put/get/containsKey and applies the sequential-key rule in `toJsonNode()`.
`PhpArrayTest` pins the rule down.

Two more `basicinformation` traps worth remembering for other modules:

- **A section with no rows contributes NO keys at all** — not `{}`, not `[]`,
  absent. Only `company` materialises empty arrays on an empty result, and only
  because its PHP has an explicit `else` (lines 222-229) that writes exactly six
  keys. `livepackage_exp` is deliberately NOT among them.
- **`class_exp` is flat while `class_ref` is nested**
  (`$jsonarray['class_exp'][$name]` — no class level). Similar-looking lines
  produce different depths; copy them literally.

Also: `PhpRows.set(row, ...)` exists for the `$row['x'] = ...` idiom. A plain
`row.put()` adds a second key when the driver reports the column under different
casing (H2 lowercases unquoted identifiers), which shows up as a stale duplicate
in test payloads. `set()` replaces in place, like PHP.

## 8. The login surface (staffsignin) — three traps, one blocker

`staffsignin/` is the only module that does NOT include `etc/restdx.inc`. Three of
its behaviours would be "fixed" by anyone tidying the API, and each would break the
client.

**8.1 There are two different dispatch rules, and they must not be conflated.**

| module family | verb source |
|---|---|
| `restdx` modules (most) | the request **body**'s `method` field wins when truthy |
| `staffsignin` | the **HTTP verb**, overridden by `?method=`; the body's `method` is ignored |

`?method=` is how two pseudo-verbs are reached that are not HTTP verbs:
`GETAUTH` and `CHKAUTH`. This lives behind `ModuleHandler.allowsQueryStringMethodOverride()`
rather than in the shared gateway, because applying it globally would change how
every restdx module resolves its verb.

Watch the empty case: `isset($_GET['method'])` is TRUE for `?method=`, and
`strtoupper('')` is `''`, which falls through to `echo "No method specified!"`.
Do not fall back to the HTTP verb there.

**8.2 No restkey gate.** You cannot present a session key before logging in, so this
module is in the gateway's `PUBLIC_MODULES` set. Adding auth here would make login
impossible.

**8.3 GET returns an empty 200, not 204.** With no query string the PHP replaces the
whole statement with `sessionId = 'xxxx'` (a sentinel matching nothing), so the
response is empty with the default 200 in place. It never calls
`http_response_code(204)`. The query string itself IS the sessionId — raw,
undecoded.

### The `PASSWORD()` blocker, solved without a data migration

`post.php` authenticates with `b.loginPassword = password('...')`, and MySQL removed
`PASSWORD()` in 8.0 — on the 8.0.40 server that statement is a hard error, so login
is already broken there.

Do not reset the stored hashes. `db/MysqlPassword` computes the identical MySQL 4.1
value in Java:

```
"*" + uppercase(hex(SHA1(SHA1(password))))
```

and the SQL becomes `loginPassword = ?`. Same result set, no schema change, no
locked-out users. `MysqlPasswordTest` pins `PASSWORD('password')` =
`*2470C0C06DEE42FD1618BB99005ADCA2EC9D1E19`. Note `PASSWORD('')` is `''`, not a hash.

Idiomatic Spring Security (`BCryptPasswordEncoder`) is deliberately NOT used: the
column contains 41-byte MySQL hashes and the client contract is unchanged. Migrating
to BCrypt is a separate decision, not part of a parity port.

### Two smaller ones

- **`uniqid()` is 13 lowercase hex chars**, not a UUID: `sprintf("%08x%05x", sec, usec)`.
  24 primary keys depend on that width. `db/PhpUniqid` reproduces it, including the
  fact that it carries no randomness.
- **`chkauth.php` echoes the upstream body verbatim** (`echo $response;`). Decoding
  and re-encoding would turn `2.50` into `2.5` and reorder keys. `RpcContext.rawJson`
  writes the bytes through untouched, and the parity test asserts exact string
  equality against the stub's body.

## 9. backenduser: two behaviours that look like bugs, and are shipped behaviour

**9.1 The update path never changes the password.** `put.php` builds its UPDATE with
`userName, loginName, level, active, instructor, companyName, email` — there is no
password column. So editing a user does not reset their password, even when the
client sends `loginPassword`. Reproduced literally; do not "fix" it without a
decision, because the admin UI may rely on editing without re-entering a password.

**9.2 Both POST and PUT return the stored password hash.** Neither blanks
`loginPassword` (contrast `staffsignin`, which sets it to `""`). `select *` plus
`json_encode` therefore ships the 41-byte hash to the client. That is a genuine
information leak in the original and is reproduced deliberately for parity. If and
when you want it closed, blank it in both handlers and tell the frontend — it is a
one-line change, but it IS a behaviour change.

**9.3 Empty-result contract differs between the two copies of post.php.** The live
`post.php` echoes `[]` with **200**. Its predecessor `post_saved_20231209.php` sends
**204**. Only `post.php` runs. Confirmed by test.

### Licence seats: the rule the code encodes

`get_licenseBatch()` / `update_license()` (now `service/LicenseAssignment`, also used
by `registerdemo/put.php`) allocate seats like this:

| `qdata.level` | meaning | licence behaviour |
|---|---|---|
| `0` | general user | consumes a seat; 405 if none available |
| `> 0` | backend/agent/instructor user | no seat at all |
Order of attempts for a general user: reuse the batch already on their
`(userId, packageId)` licence row, else take the company's agent's first batch with
`licenseVolume > licenseUsed`, ordered by `issueDate` then `availableTill`.
A licence created here has **NULL agentId** (the INSERT omits it), which is why the
real `license.agentId` must be nullable, and why such rows never appear in
`agentlicense_view`.

One ordering trap: the PHP assigns `$qdata->userId = uniqid()` **before** calling
`update_license`, so the newly minted id is what lands on the licence row. Passing
the pre-assignment (empty) id silently attaches licences to user `""`.
