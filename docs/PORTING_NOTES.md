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

**9.1 The update path never changes the password — by design, not by accident.**
`put.php`'s UPDATE carries `userName, loginName, level, active, instructor,
companyName, email` and no password column, so sending `loginPassword` to an update
does nothing.

That is not an oversight: the system has **no per-user password management for
general users at all**. There is ONE shared password per company, stored in
plaintext in `company.commonPW` (created/edited by `package/put.php`), and it is
pushed into every non-instructor account whenever a drill is activated —
`controldrill/put.php:24`, commented "refresh password on drill activation":

```php
if ($mod==0) {
  $sqlx = "UPDATE backenduser set loginPassword = password('".$row['commonPW']."')
           where companyName = '".$row['packageId']."' and instructor = 0";
}
```

So any password typed while creating a user is effectively temporary: the next
activation with `mod=0` overwrites it with the company password. `instructor = 1`
accounts are excluded from that UPDATE and therefore DO own their password — which
is exactly why `backenduser/put.php` still accepts `loginPassword` on INSERT.

Port implications, so this is not rediscovered the hard way:

- `controldrill/put.php` must be ported with the same bulk reset, using
  `MysqlPassword.encode(company.commonPW)` in place of `password()`. The WHERE clause
  must keep `instructor = 0`, or instructors lose their own passwords.
- Keep the plaintext `commonPW` column as-is. It is a design flaw, not a porting
  task, and changing it would break the admin's "reset everyone" workflow.
- `backenduser/put.php` keeping the no-password UPDATE is correct behaviour to
  preserve. Do not "complete" it by adding a password column.

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

## 10. The drill report family (drilloverview / drilleffective / drillcapable)

Three modules share one query shape and one detail-row source, but each renders it
differently. All three read `drill` joined to `drillcontrol` and `backenduser`, then
for each drill read **only the latest step**:

```sql
select *, '<drillControlId>' as drillControlId from drilldetail
where drillId = ? and stepNumber = (select max(stepNumber) from drilldetail where drillId = ?)
```

**10.1 `json_decode($x, true)` — assoc mode, and it changes the output.** These
modules decode `drilldetail.content` with `true` (basicinformation used the object
mode). In assoc mode a JSON object becomes a PHP array, so `{"0":"a","1":"b"}`
re-encodes as `["a","b"]` and `{}` becomes `[]`. `db/PhpJson.decodeAssoc` returns
`PhpArray`/`List` for exactly this reason — feeding a Jackson tree straight to the
response would emit `{"0":"a","1":"b"}` and silently differ.

**10.2 The `wlwl.` database qualifier is dropped.** The PHP says
`from wlwl.backenduser`. The JDBC connection's catalog is that same database, so the
result is identical — and dropping it is what lets the service point at a renamed
database at cutover. Recorded as a deliberate divergence.

**10.3 `ORDER BY` is qualified to `drill.*`.** MySQL resolves unqualified ORDER BY
names against the SELECT list first, and the select list is `drill.*`, so
`order by drillControlId, seq` means drill's columns. Writing it explicitly is the
same column and avoids ambiguity errors on other engines.

**10.4 Blank records are not counted.** A drill with no `drilldetail` row appends a
blank record (all zeros, status from `drill.drillStatus`) but does **not** increment
`$count`. If that is the only drill, `$count` stays 0 and the response is 204 — the
blank record is discarded. When other drills do produce rows, the blank record rides
along and keeps whatever rank it was given (usually 0), because the re-rank only runs
for drills that had detail rows.

**10.5 Per-module oddities.**

| module | notable |
|---|---|
| `drilloverview` | Ranks by `sales` DESC; on a tie, `teamEff` **ascending** (lower efficiency first). Ranking runs after *every* drill, so only the last pass is observable. |
| `drillcapable` | Ranks by `totalCompetency` DESC, NULLs last. The comparator never returns 0, so it is not a valid ordering — reproduced element-wise rather than "cleaned up". |
| `drilleffective` | Header is built from the first row's `analysis.action[]` (oneInFour==1 group first). **Nothing is sorted or ranked** — those blocks are commented out — so rows keep join order and there is no `rank` key. `round()` output stays a FLOAT, so JSON shows `50.0`, not `50`. |

**10.6 `drilleffective` appends its footer into `detail`.** `buildFooter()` runs
after the loop and does `$detail[] = $totals`, so the response carries one more detail
row than there are drills, and that last row's `userName` is `""`. It also emits the
running totals as a top-level `fb` object. Both are load-bearing for the client's
table rendering.

`buildFooter` also runs when the loop found nothing, which is why the port must not
short-circuit before calling it — the 204 path discards the result, but the control
flow has to match.

## 11. model/: three PHP bugs that are part of the contract

`model` resizes a JSON document's arrays to match counts declared on the template row.
The repair pass contains three defects that a "clean" rewrite would silently fix, and
each changes output.

**11.1 `!==` on a value that is always a string.** The staff check is
`$row['numberOfStaff'] !== count($row['modelContent']->staffs)`. mysqli returns numeric
columns as STRINGS, and `count()` returns an int, so `'2' !== 2` is **always** true and
the staff list is rebuilt on every row, even when already the right size. The
environment and recruit checks use loose `!=`, so those rebuild only on a real
mismatch. Reproduced: `staffs` always rebuilt, the other two only when counts differ.

**11.2 `$rov` is never reset between rows.** The rebuild appends its padding to a
scratch array that lives for the whole request, so a second model inherits the first
model's padding and is then partially overwritten:

```php
for ($c=0; $c<$row['numberOfStaff']; $c++) { $rov['staffs'][] = $staffRec; }  // grows
for ($c=0; $c<min($n, count($existing)); $c++) { $rov['staffs'][$c] = $existing[$c]; }
```

With one model per template this is invisible; with two it produces arrays longer than
the template count. The port shares a single `rov` across the loop to match.

**11.3 A zero-size rebuild stores NULL, not `[]`.** If the wanted count is 0 and the
blob has no such key, the append loop never runs, `$rov['staffs']` is undefined, and
the assignment stores null. The key appears as `null` in the JSON, not `[]`.

Also note: `modelContent` must arrive as JSON **text**. If the client sends an object,
PHP interpolates a stdClass into SQL and fatals; the port throws so the gateway returns
500 for the same reason. And `put.php` decodes but does NOT run the repair pass — only
`post.php` repairs.

## 12. The profile matrix modules (profileimpact / profileaffect / profilesens)

Three modules around one idea: a matrix with one row per (skillsClass, moraleClass,
efficiencyClass) combination, seeded lazily on first read.

**12.1 `profileaffect` and `profileimpact` share a table AND a post.php.** Their
`post.php` files are byte-identical and both read `profileimpact`; the affect and
impact column sets live on the SAME rows. Only `put.php` differs: `Impact*`/`impactMsg`
versus `Affect*`/`affectMsg`. Hence the shared `AbstractProfileImpactModule` in the
port, with each subclass supplying its column prefix — and hence these two cannot be
tested independently of each other.

**12.2 Seeding enumerates a cartesian product of ONE class per profile.** The three
nested loops each filter `profileName == profiles[i]`, where `profiles[]` comes from
`baseinfo where baseclass = 'profiles'` **in fetch order**. With 2 classes per profile
that is 8 rows. If fewer than three profile names exist, `$profiles[1]`/`[2]` are
undefined, nothing matches, and the caller returns 204.

Seeding is triggered differently: `profileimpact` seeds when the rows **for that
impactClass** are empty; `profilesens` seeds only when the **whole table** is empty
(it has no impactClass column).

**12.3 The two put.php files interpolate NUMBERS unquoted.** A non-numeric value is an
SQL syntax error there, i.e. a 500. The port throws rather than coercing, so the
status code is unchanged. The same applies to the key: `where profileImpactKey = <raw>`
is unquoted in put.php but **quoted** (`'...'`) in post.php — which is why a non-numeric
query string simply matches nothing on POST but 500s on PUT.

**12.4 `profilesens/put.php` returns FEWER fields than its post.php.** POST adds three
`*Exp` (profileclass display name) and three `*Prf` (baseinfo.displayKey) fields; PUT
adds only the `*Exp` three. Faithful, and asserted by test.

`*Prf` is worth noting: it is `baseinfo.displayKey` — the short code such as `SK` —
obtained through the cross join `from profileclass, baseinfo where profileName = name`,
not from profileclass.

## 13. The catalogue modules (environmental, template, product, style, task, agent, licensebatch, package)

Eight modules with one shape, so one base class (`AbstractCatalogueModule`): list
(+optional LIMIT) → array/object by query string → 204 when empty; PUT = upsert, then
re-read, then the same response shape.

**13.1 The PUT re-read is keyed on `$qdata->name`, not on the new key.** For the five
baseinfo-backed catalogues the re-read is the module's own listing query filtered by the
BASEINFO display name. Inserting an entity whose name has no baseinfo row writes the row
and then answers **204**, because the join finds nothing. This is why the entity tables
are effectively second-class citizens of baseinfo, and it is asserted by test.

**13.2 POST uses a LEFT JOIN but the re-read uses an INNER JOIN** (environmental,
template, action). So a baseinfo row with no entity row appears in the list (with null
entity columns) but never in a PUT response.

**13.3 baseclass values are PLURAL** in the module filters: `'actions'`, `'products'`,
`'tasks'`, `'styles'`, `'environment'`, `'template'`, `'profiles'`. Also note
`'styles'` is shared between the driver-style profile rows and the style catalogue, so
`style/` legitimately returns rows with a null style half.

**13.4 `product`'s INSERT hardcodes `productName = 'basic-product'`** — all new
products collide on the primary key unless the row already exists. Its UPDATE keys on
`productShortName = $qdata->productName`, which is a different field from the branch
condition (`$qdata->productName`).

**13.5 `licensebatch`'s PUT has no update branch.** Every PUT mints a new `uniqid()`
batchId and inserts, so editing a batch from the UI creates another one.

**13.6 `profileclass`'s PUT takes its TABLE NAME from the request.** `$fileType =
$data->mod` is interpolated as an identifier: `"insert into ".$fileType`. That cannot be
parameterised, so the port accepts only a bare identifier (`[A-Za-z_][A-Za-z0-9_]*`) and
answers 500 otherwise — the same status the SQL error would produce. The legitimate
value is `profileclass`.

**13.7 `taskefficiency` has a query-string bug.** Its filter appends
`and profileImpactKey = '<qs>'` to a table with no such column, so ANY query string is
an SQL error (500) in production too. Reproduced by issuing the same statement, and
tested. Its PUT also interpolates its numbers unquoted.

## 14. Schema reconciliation against the production dump (2026-10-26)

The real `mysqldump --no-data` arrived after 23 modules were already ported. The
reference is now `/opt/data/wlwl_schema_real.md`. Everything below was found by
diffing it against `src/test/resources/schema-test.sql`, and the fixture has been
corrected so the tests assert against production shapes rather than guesses.

**14.1 The three licence views were FICTION in the fixture and are now transcribed.**
This mattered, because three ported modules read them:

| view | production definition | what the fixture used to say |
|---|---|---|
| `agentlicense_view` | `agent LEFT JOIN licensebatch`, **GROUP BY agentId, batchId**, computed `licenseUsed` | an inner join to `license` — wrong cardinality |
| `batchlicense_view` | `agent JOIN (licensebatch LEFT JOIN license)`, GROUP BY batchId, **ORDER BY availableTill**, `licenseUsed = count(license.licenseId)` | a plain select from `licensebatch` |
| `license_view` | `license JOIN licensebatch` | a select from `license` with invented columns |

Consequences now encoded: an agent with two batches appears **twice**, an agent with
no batch still appears (NULL batch columns), and `licenseUsed` is a COUNT, not a
stored column.

**14.2 `licenseUsed` exists nowhere as a column.** It is computed by both views. Any
test that "proved" seat allocation was really reading the fixture's own invented
column. The Java already queried `batchlicense_view`, so the code was right — the test
was not.

**14.3 `license` has no `agentId`, no `availableFrom`, no `availableTill`.** A licence
hangs off a `batchId`; seat and date data come from `licensebatch` through the views.

**14.4 `company` has no `creditBalance` and no `lastSeen`** (both were invented) and
DOES have `activeOne` / `activeTwo` / `activeThree`, one per run slot.

**14.5 `drill.drillStatus` is an INT** (FK to `drillstatus`), not a string. So the blank
records emitted by drilloverview / drilleffective / drillcapable carry a NUMBER.

**14.6 `drilldetail`'s key is `drillDetailId`** (capital D), it has a NOT NULL `action`
column, and `stepNumber` defaults to 0.

**14.7 `basicinformation`'s agent "live" filter reads a column that does not exist.**
It tests `($row['availableFrom'] <= $timeNow) && ($row['availableTill'] >= $timeNow)`
over `agentlicense_view`, which exposes only `availableTill`. `$row['availableFrom']` is
therefore null, and `null <= 'YYYY-MM-DD'` is true, so that half of the condition is
**always true** — the live filter is effectively `availableTill >= today`. Preserved
as-is; it is why `agent.availableTill` is NOT NULL in production.

### Still to reconcile (code-side, not yet changed)

These are real type mismatches that MySQL silently coerces and H2 does not, so they
need changing before the port runs against H2 or a strict SQL mode:

1. **`action.actionType` is `varchar(100)`** (FK `actiontype.actionType`), but
   `ActionModule` binds it as an int. The PHP passes it as a string.
2. **`action.affectedIf` is `varchar(10) DEFAULT '>'`** — a comparison OPERATOR, not a
   baseinfo key. `affectCode = $binfo[$row['affectedIf']]` is therefore normally null.
3. **`taskefficiency` has no `efficiency` column** (the fixture invented one) and
   `passingRate` is INT, `target` is decimal(5,2).
4. **`template.report` is INT**, not a string.
5. **`agent.availableTill` is `date NOT NULL`** with no default, so agent creation must
   always supply it.
6. `action` actually has 31 columns (evaluation, perWeek, `1In4`, useLimit, …); the PHP
   only writes 14 of them, so untouched columns rely on their DDL defaults.

Also note the tables are **utf8mb3**, not utf8mb4, and `restsession.usedOn` really is
`varchar(100)` (the PHP stores a datetime string plus microseconds there).

## 15. The last tier (license, packageremove, controldrill, registerdemo, simulator, consoleuser)

**15.1 `license`** filters with an OR, not an AND:
`where batchId = '<mod>' or packageId = '<mod>'`. One endpoint therefore answers both
"licences in this batch" and "licences for this customer", distinguished only by what
the caller passes.

**15.2 `packageremove` answers 204 unconditionally**, even when the DELETE fails — the
PHP never inspects the mysqli result. The port catches and logs the exception instead
of letting it become a 500, because a 500 here would break a fire-and-forget call.

**15.3 `controldrill` is the third meaning of `mod`.** `$act = array('active',
'activeOne', 'activeTwo', 'activeThree')` and `UPDATE company set $act[$mod] = 1`.
An out-of-range index makes the statement `UPDATE company set = 1`, an SQL error, so
mod >= 4 is a 500 — reproduced by throwing at that point.

`mod == 0` additionally re-pushes the company password:

```php
UPDATE backenduser set loginPassword = password('<commonPW>')
 where companyName = '<packageId>' and instructor = 0
```

That is the mechanism behind §9.1. The `and instructor = 0` MUST stay: without it,
instructors lose their personal passwords. It runs once per company row, inside the
fetch loop. Tested by asserting a non-instructor's hash is replaced with
`PASSWORD(commonPW)` while an instructor's is untouched.

**15.4 `registerdemo` is on the PUBLIC surface and dispatches on the HTTP verb only.**
Its `index.php` never includes `restdx.inc`, so the body's `method` field is ignored
(new `ModuleHandler.ignoresBodyMethod()` capability flag), and anything but PUT answers
the literal `No method specified!`. Flow: blank companyName becomes `'admin'`; a
taken `loginName` (for a different userId) is 406; no licence seat is 405; then insert
(`uniqid()` id, `PASSWORD()` hash) or update — and the UPDATE deliberately does NOT
write `loginPassword`.

**15.5 `simulator` has two shapes.** POST always returns an ARRAY, even with a query
string present (it appends unconditionally, so there is no object switch) — the only
module like that besides `consoleuser`. PUT's initialisation branch uses `rand($min,
$max)`, which is INCLUSIVE at both ends, so those values are asserted by range, not by
value; the action branch is deterministic and asserted exactly. `$qdata->skillsValue
=== -1` is a STRICT comparison, so only a real numeric -1 triggers the roll.

**15.6 `consoleuser` is the only report that keeps `drill.startOn is not null`.** The
three other drill reports have that predicate commented out. It also returns the whole
`drilldetail` row with `content` re-encoded — a pure round-trip through assoc-mode
decode, which is why it could be ported without knowing the blob's internal keys.

### Test-fixture traps found here

- Two Spring contexts (one plain, one with MockMvc) share the same in-memory H2
  database because of `DB_CLOSE_DELAY=-1`. The second init re-runs the schema, so every
  view must be dropped BEFORE the tables it references, or `drop table staff` fails
  with a dependency error. `schema-test.sql` now drops the simulator views first.
- `assertThat(intValue).isEqualTo(Math.round(...))` compiles but always fails:
  `Math.round` returns a long, and `Integer.equals(Long)` is false. Cast to int.

## 16. signin/ is dropped, not ported (ruling)

`signin/` (6 files, 144 LOC) is **out of scope by explicit user ruling**. Three
independent findings, any one of which would have been enough:

1. **Its tables do not exist.** It reads `wxuser` and `studentlist` and writes `tasks`;
   the production dump contains 44 objects and none of them. Its login is broken even
   past that: `insert into restsession (sessionId, userId, restkey)` omits `createdOn`,
   which is `datetime NOT NULL` with no default — the working `staffsignin/post.php:31`
   passes `now()`, so this is a defect rather than a variant.
2. **Nothing calls it.** The Vue app issues 45 distinct `backend/...` URLs and every one
   is a hardcoded literal — no template literal, no dynamic module name — so the set is
   exhaustive. `backend/signin/` is not in it; `backend/staffsignin/` is (7 refs). All
   other "signin" tokens in `src/` are help screens (`Singin_help.vue`,
   `Helps.signin-*`, `images/signin.png`). There is no `dist/` build to hide a call.
3. **It was switched off on purpose.** `vue.config.js` still carries a commented-out
   `pathRewrite: { '^/backend/signin/' : '/backend/signin/index.php' }` in both proxy
   blocks.

Consequence for behaviour: `/backend/signin/` returns **404** here, where the PHP would
fatal into a **500**. That divergence is deliberate and unobservable. `signin` was also
removed from the gateway's `PUBLIC_MODULES` set, since there is no handler to route to.

Related, recorded while verifying: the frontend calls five endpoints that have **no**
backend directory (`idx_baseinfo`, `idx_baseclass`, `idx_staff`, `idx_profileclass`,
`student` — 15 call sites). Those 404 in PHP production today too, so the gateway's 404
is faithful; they are missing features upstream, not porting gaps.

## 17. messages/ is dropped, with the risk accepted and written down

`messages/` (3 files, 51 LOC) is **out of scope by explicit user ruling**. It needs
`smshead_view`, `smslist_view` and `StudentList`, none of which exist in the dump; it
also has a `switch` with no `default` branch (so an unknown `mod` leaves `$sql` undefined
and the request dies), and it splices `$data->classes` raw into `in (...)`.

**This drop is riskier than `signin`'s, and the difference matters:** the frontend still
contains a caller. `src/views/Message.vue:65` fetches `/backend/messages/` with the full
envelope (`restkey`, `mod: 'head'|'list'`, `classes`, `limit`, `qdata.SMSNumber`). The
view is unreachable today — it is absent from `src/router/index.js` and imported nowhere
— but one router line revives it, and every call then hits a **404** here (the PHP would
500 on the missing views). Because the failure is a 404 rather than a 500, it will be
quiet on the server side and visible only as a broken screen.

Mitigations, in order of usefulness:

1. `BackendGatewayParityTest.droppedModulesAre404` asserts 404 for **both** dropped
   modules. It is the ruling encoded as a test, and its comment tells the next developer
   not to "fix" the 404 into a stub.
2. The ledger's **risk register (R1–R4)** records the failure mode, the false-assumption
   trap (the PHP directory still exists), the possibility that the SMS schema lives in a
   different database, and the fact that no data is at stake (the module is read-only).
3. No placeholder, stub or empty handler exists in the Java tree — deliberately. A stub
   returning 200 would conceal the gap instead of exposing it.

## 18. `drilldetail` is an event log of full state snapshots (drill/ architecture)

This is the single most important structural fact for porting `drill/` (5,617 LOC).

`drilldetail` is **append-only**, and **every row contains the complete game state** as
JSON in `content`. A 42-step drill produced 42 rows totalling ≈6.7 MB, because each
snapshot embeds the whole history: `content.summary` is a growing array that reaches 40
entries, so the last row repeats every earlier one. This is why the blobs are
150–270 KB and why `drill/` is a state machine rather than CRUD. **Do not deduplicate
it** — the reports read `summary[0]` as "the baseline of that snapshot".

### The `action` column names the NEXT pending step, not the one just taken

`action.php` decides the label after processing and before inserting:

```php
$qdata->dayNumber++;                                   // an action always burns a day
if ($content['day'][$qdata->dayNumber]['evaluateStyle']) {
    $qdata->action = "SELECT_STYLE";
} else {
    $qdata->action = "MORE_ACTIONS";
    $content['control']['action'] = '';                // cleared on non-style days
    $content['control']['task']   = '';
}
```

Verified against real data: all six rows immediately preceding a `SELECT_STYLE` still
carry an action name; the 8 rows with a blank `control.action` (steps 3, 8, 9, 18, 19,
23, 28, 40) and none of them precedes a style pick.

### The real unit is a WEEK of 5 days, not "a set of 5 records"

`INTRODUCTION` is step 0 / day 0. After that the drill runs in **weeks of 5 days**:

```
day 1 of the week  -> SELECT_STYLE   (pick the style for the week)
days 2,3,4,5       -> one action each
```

Verified: `SELECT_STYLE` lands on days **1, 6, 11, 16, 21, 26, 31** — exactly every fifth
day. Counting *records* per set instead gives the misleading 5,6,6,5,5,4,2, because extra
rows are written on environment days (below). The last week is short: this drill ended at
day 33, i.e. day 3 of week 7 (days 31-35).

`content.week` carries `weekNumber`, consistent with 5-day weeks.

### THE CENTRAL RULE: the backend is STATELESS and the client owns the game state

Confirmed by the product owner. The frontend decides what happens — including what an
environment event *does* — and submits the outcome. The backend has **no memory of what
happened previously**; it processes what it is handed, appends a snapshot, and replies.
The `drilldetail` rows are a log the **client** drives, not a server-side machine that
re-reads its own history.

Consequences that govern the whole `drill/` port:

1. **Never "improve" a writer by re-reading state from the DB.** `environment.php` line 4
   and `timeout.php:9` build their snapshots from `$qdata->content` — that is the design.
2. **Each `drill/` endpoint is a pure function**: (submitted state, submitted event) ->
   (recomputed state, appended snapshot). Much easier to port faithfully than a stateful
   machine, but it does mean Java must consume client-supplied content, not reconstruct it.
3. **Do NOT infer per-day row invariants from the data.** An earlier draft claimed "one
   row per day, plus one per environment event". That over-fit a client behaviour: a day
   holds more rows simply because the client posted more requests (double submit, a
   near-timeout post, an event plus its acknowledgement). The backend appends what it is
   asked to append.
4. **Trust model (documented risk, not fixed):** the client can submit arbitrary content,
   so the persisted history and every derived report are only as trustworthy as the
   browser. Preserving that is parity; hardening it is a separate, explicit decision.

### `content.environments` is a SCHEDULE, not a log

It is a 6-element list, **byte-identical in all 42 rows** — computed at init, never
appended:

```json
{"environmentName":"Financial-Crisis","environmentExp":"_u.e_fin_crisis","happenOn":"7"}
```

`happenOn` is a **day number as a string**. This drill's schedule:

| day | event |
|---:|---|
| 3 | new-crm |
| 7 | Financial-Crisis **and** morale-dropped |
| 15 | critise-product |
| 18 | 360-feedback |
| 22 | efficiency-dropped |

The schedule is data the **client** walks. `init.php` expands it into
`content.day[<n>].event = {count, eventName[], eventExp[], staffName[], criteria[]}`; when
that day arrives the frontend submits the event and `environment.php` applies it. So the
extra rows on days 3, 7, 15, 18 and 22 are client-driven submissions, **not** a backend
rule — day 15's second row is a double submit, not something to reproduce.

**`environment.php` edits before it appends.** Its first statement (line 4) is:

```php
update drilldetail set content = '<$qdata->content>', updatedOn = now()
 where drillDetailId = '<$qdata->drillDetailId>'
```

i.e. the client's submitted snapshot **overwrites the row the client was on**, and only
then does the module process the event (`$content['day'][$dayNumber]['event']`) and
INSERT a new row. So one environment call = 1 in-place update + 1 append. Missing the
order would double the history.

### Two timeout paths (both client-detected)

1. **Frontend timer fires** -> the client submits, `timeout.php` writes the terminal
   snapshot: `900` + `COMPLETED` if the drill had reached 600, else `800` + `TIME_OUT`.
2. **A user submission arrives after the exercise end time** -> the client marks it, which
   is what leaves the near-terminal blank-`control.action` row (this sample: step 40,
   day 33) immediately before the terminal row.

`action.php`'s own clock check is **commented out** — `// } else if ($res['now'] >
$res['expireOn']){ $stat = 800; ... }` — so the server never decides expiry by itself.
Expiry is always detected client-side, then submitted.

### Status ladder

| status | meaning | set by |
|---:|---|---|
| 200 | initialised, not started | `init.php:228` |
| 500 | in progress | `init.php` / carried forward |
| 600 | **target achieved** — does NOT end the drill | `action.php:784` |
| 700 | failed: `dayNumber >= numberOfDays` | `action.php:797` |
| 750 | failed: `noMoreStaff()` | `action.php:800`, `environment.php:190` |
| 800 | timed out | `timeout.php:15` (`status == 600 ? 900 : 800`) |
| 900 | completed successfully | `action.php:797/800`, `timeout.php:15`, `environment.php:190` |

A drill ends only on days-exhausted, no-staff-left, or clock expiry. Reaching 600
(target met) is **not** terminal — the sample hit 600 at step 32 and kept running to
step 41.

600 is deliberately **not** an end state: the exercise lets the user keep playing after
the target is hit (confirmed by the product owner), so 600 means "target achieved, still
in progress". Two consequences for the port:

- **Reporting caveat.** `drilloverview`/`drillcapable`/`consoleuser` print
  `control.status` raw, so a row showing **600** means the target was met but the drill
  had not finished — either still running, or **abandoned** (browser closed, so
  `timeout.php` was never called and the last row was never stamped 900). The UI must
  not render 600 as "completed". Only 900 is a successful completion; 700/750/800 are
  failures/timeout.
- **Never treat 600 as terminal in `drill/`.** Any short-circuit that closes the drill
  on `ordersCompleted >= ordersToComplete` would end runs early and lose the remaining
  steps — `action.php:780-789` only *sets* 600 and continues.

### Terminal labels

`COMPLETED` (when status was 600) / `FAILED` (days or staff exhausted) / `TIME_OUT`.

`timeout.php` behaves differently from `action.php` in two ways that are visible in the
data and easy to get wrong:

1. It does **not** advance `dayNumber`; it reuses the client's value. The sample's
   terminal row has `dayNumber = 33`, identical to its predecessor — which is how we
   know it came from `timeout.php` and not `action.php` (an `action.php` row would be
   day 34).
2. It builds the new snapshot from **`$qdata->content` sent by the client**, not from a
   DB re-read, and it first stamps the *previous* row's `updatedOn = now()` using the
   incoming `drillDetailId`. The port must reproduce that: the timeout record trusts
   client-supplied state.

### Writers of `drilldetail`

| file:line | record |
|---|---|
| `init.php:312` | `'INTRODUCTION'`, step 0, day 0, status 200 |
| `action.php:867` | next-step label (`SELECT_STYLE`/`MORE_ACTIONS`), day incremented |
| `timeout.php:29` | `COMPLETED`/`TIME_OUT`, day NOT incremented |
| `environment.php:208` | `$qdata->action` as sent; does not clear `control.action` |

`environment.php` is the remaining piece of the model; the non-slot records inside a set
are currently attributed to it pending confirmation.

## 19. drill/ endpoints are INDEPENDENT — no ordering between them

Confirmed by the product owner: `init`, `SELECT_STYLE`, action, `environment` and
`timeout` have **no sequence relationship**. Each is a separate entry point that handles
the record in hand; the dataset (`drilldetail`) is simply the collection of all records
that arrived. Nothing in the backend requires them to be replayed in order, so each can
be ported and tested on its own. `drill/index.php` is just
`include("../etc/restdx.inc")`, so `drill` is a normal **authed** module on the standard
verb dispatch — there is no custom router.

### `drill/timeout.php` contract (29 lines, but not standalone)

It calls `saveCompetency()` from `competency.php` (173 lines), which writes the
`competency` **table** — so those two must be ported together. Sequence:

1. Read the last row: `select * from drilldetail where drillId=? order by stepNumber desc limit 1`.
2. **Guard:** if that row's `action` is already `TIME_OUT`/`COMPLETED`/`FAILED`, skip the
   write entirely — but still fall through to step 11, so a repeated call returns the
   existing terminal row with **200**, not 204. (Note: if there is no row at all,
   `$row['action']` is null, all three comparisons are true, and the write path runs.)
3. `update drilldetail set updatedOn = now() where drillDetailId = <qdata->drillDetailId>`
   — marks the row the *client* was sitting on, which need not be the row read in step 1.
4. Decode `$qdata->content` (client-supplied state — see §18).
5. `drillDetailId = uniqid()`, **`stepNumber++` from the client's value, never from the DB**.
6. `stat = (control.status == 600) ? 900 : 800`.
7. `action = (control.status == 600) ? "COMPLETED" : "TIME_OUT"`.
8. `update drill set drillStatus = stat [, endOn = now()] where drillId = ?` (`endOn` only
   when `stat > 600`).
9. `update drillcontrol set completedDrill = (select seq from drill where drillId = ?)
   where drillControlId = (select drillControlId from drill where drillId = ?)` — runs
   when `stat > 500`, so effectively always here.
10. `content.control.status = stat`; `saveCompetency(...)`; INSERT the new snapshot with
    `json_encode($content, JSON_UNESCAPED_UNICODE)`.
11. Re-read the last row, `json_decode` its content, **200** with the row as an object;
    **204** only if no row exists.

Quirks to preserve: the step number comes from the client; the guard is based on the
last row while the `updatedOn` stamp uses the client's id; and unlike `action.php` /
`environment.php`, this endpoint does **not** add a `timeNow` field to the response.

## 20. Two blob GENERATIONS — fixtures must cover both

Two real drills were dumped: 2023-12 (42 rows) and 2026-08 (43 rows). The `content`
document changed shape between them, so any fixture that only models one generation will
pass while the other silently yields nulls.

| feature | 2023-12 blob | 2026-08 blob |
|---|---|---|
| `content.competency` + `content.avg_competency` | **absent** in all 42 rows | **present in exactly 1 row — the terminal one** |
| `analysis.action.<name>` keys | `name,count,positive,negative,oneInFour` (5) | + `skillsAdded,moraleAdded` (7) |
| `analysis.action.total` | int (97) | int (59) |
| `analysis.action` container | **object** | **object** (unchanged) |

This is not a bug in the reader: `saveCompetency()` runs only on the terminal save
(`timeout.php:27`, `action.php` end-conditions, `environment.php`), and it mutates
`$content` **before** the terminal INSERT — so competency appears once, in the last row,
and only on builds that write it into the blob. `drillcapable` is therefore correct as
written and legitimately returns nulls for older drills.

`content.avg_competency` was **all zeros** in the 2026-08 drill, confirming the
`if ($rt['cnt'] > 1000)` threshold: peer averages are zeroed unless more than a thousand
peers share the `seq` **and** `duration`.

Both generations confirm the calendar: `SELECT_STYLE` lands on days
`1,6,11,16,21,26,31,36` — every fifth day (§18), 8 weeks in the 2026-08 drill.

Both drills also ended the same way — terminal row sharing its predecessor's `dayNumber`
(2026-08: day 37 with `numberOfDays` 39, `remainingMinutes` `'0:05'`), i.e. written by
`timeout.php`, not `action.php`.

## 21. drill/ sub-module tally (running state of the port)

`drill/` is one module with an internal switch (`put.php`) plus a `post.php` that ignores
`mod` entirely. 18 files, 5,617 LOC, but **5 are backups** (`action_saved_20230919`,
`bossreview_saved_20230919`, `commonLib_20230924`, `commonLib_saved_20230910`,
`commonLib_saved_20230920_change_In_Skills_Treatment`) — the live surface is **2,798 LOC**.

| unit | PHP LOC | state |
|---|---:|---|
| `drill/put.php` switch | 28 | **ported** — falsy/`0` mod emits nothing, unknown mod echoes `No Mod` |
| `drill/reset.php` | 18 | **ported + tested** |
| `drill/updatename.php` | 23 | **ported + tested** |
| `drill/timeout.php` | 50 | **ported + tested** (`DrillTimeoutParityTest`) |
| `drill/competency.php` | 173 | **ported** — `saveCompetency()` only, `Competency.java` |
| `drill/commonLib.php` | 775 | **complete — 22 of 22 functions** in `DrillCommon.java` |
| `drill/init.php` | 350 | **ported + tested** (`DrillInitParityTest`, 8 cases) |
| `drill/environment.php` | 232 | **ported + tested** (`DrillEnvironmentParityTest`, 5 cases) |
| `drill/action.php` | 891 | **PARTIAL** — `SELECT_STYLE` ported + tested; `MORE_ACTIONS` passes 1–2 (`applyRecruits`, `applySwapTask`) written + unit-tested but **not wired into the dispatch**; passes 3–5 unported, so every non-`SELECT_STYLE` action answers 501 |
| `drill/bossreview.php` | 108 | **ported + tested** (`BossReviewTest`, 4 cases) |
| `drill/post.php` | 60 | **ported + tested** (`DrillPostParityTest`, 6 cases) — POST verb, query string = userId |

`DrillCommon` present: `writeHistory`, `dbg`, `readParams`, `memberWhere`, `currentMembers`,
`noMoreStaff`, `initMemberValues`, `init_staff_ana`, `highEfficiency`, `lowEfficiency`,
`highMorale`, `lowMorale`, `cal_impacts`, `cal_affect`, `writeSummary` (plus helpers
`truthy`, `named`, `hasName`, `isStrictlyZero`, `extreme`, `Pick`).

`DrillCommon` is COMPLETE: all 22 commonLib functions are ported, and `bossreview.php` is
ported too, so the only thing left in `drill/` is `action.php`'s `MORE_ACTIONS` branch —
see §29 for its five-pass decomposition and current state.

Notable gap inside a ported function:

- `cal_impacts()`'s `evaluate` branch calls `cal_swap()` (ported) but the `$tm` message
  assembly belongs to action.php's evaluate flow, so it still throws
  `IllegalStateException`. That is the first item of the ACTION slice.
- `cal_impacts()`'s `$eff` parameter (task skill/morale weights) is only read inside the
  PHP's `if (false) { ... }` dead block, so the port omits it.
- `cal_affect()` is called by `environment.php` with 8 arguments against a 9-parameter
  signature, so the PHP's `$day_number` is null and `null + 2` is 2 — the port hard-codes
  that reading of `actEffect`.
- **Column case trap:** every value read out of a profile row MUST go through
  `PhpRows.get()`. MySQL reports `skillsImpact`, H2 lowercases it to `skillsimpact`, and a
  bare `Map.get()` silently returns null — which makes every impact factor compute as 0
  with no error at all. This cost a debugging round; `DrillCommon.rowGet()` exists for it.
- `writeSummary()` divides every average by `control.maxMembers`, not by the live
  headcount (the PHP's `currentMembers()` result is overwritten on the next line), and its
  trend block runs for empty slots, auto-vivifying `members[$c]` on write.

Unported branches answer **501 with the mod name in the body**, deliberately: a silent
`No Mod` or an empty 200 would let an unported branch look like a working one.

Sample data: the two real dumps in `/opt/data/pastes/paste_3_033955.txt` and
`paste_4_051615.txt` (drillDetailId, drillId, stepNumber, dayNumber, action, content …)
are **not** RFC-quoted CSV — `content` is a raw JSON blob containing commas and quotes.
Parse with `json.JSONDecoder().raw_decode` at the `{` after the `action` field, not
`csv.reader`. `/opt/data/samples/shape_report.py` does this.

## 22. drill/ mod=INIT — three traps and one open question

`drill/init.php` is the only place a drill's state document is CREATED. It is also the
messiest file in the module: six nested `if ($result->num_rows)` blocks, two separate
copies of `params_int`, and a response whose status and body are decided by two different
variables.

**22.1 The body comes from the last SELECT, the status from `$err`.** Every query in the
file overwrites `$result`, so what gets echoed is whatever the LAST query returned — after
a successful build that is the re-read `drilldetail` row. The status is a separate
variable: `$err = 404` when the drill is unknown, `$err = 204` once it exists, and the file
ends with `if ($err) http_response_code($err);` *after* echoing. On the build path that
trailing call is a no-op, because the multi-kilobyte echo has already flushed the headers
(the INIT document is always far larger than PHP's 4096-byte output buffer) — that is the
only way the Vue client, which only acts on `res.status === 200`, can ever work. So:

| path | body | status |
|---|---|---|
| drill has drilldetail rows already | last row | **200** |
| drill exists, content built | new row | **200** |
| drill exists, a join fails (template/model/product) | none | **204** |
| drill unknown, or `drillId` missing | none | **404** |

`DrillInit` applies `$err` only on the no-body path, and says so in a comment. Do not
"restore" the trailing status call: on Tomcat it would set 204 on a written response.

**22.2 The environment `criteria` bug.** Inside `for ($c=0; $c<$duration; $c++)` the event
fields are appended to `$content['day'][$here]` but `criteria[]` is appended to
`$content['day'][$when]` — the event's FIRST day. For a one-day event they coincide; for
a longer one the criteria lands on the wrong day. Preserved verbatim.

**22.3 Types: PHP only numbers what PHP computed.** See 22.4 — the INIT document is the
clearest evidence for this anywhere in the codebase, because it mixes both in one field:

| field | source | JSON |
|---|---|---|
| `day[n].newOrders` (no event) | copied from `product.ordersPerDay` | `"3"` |
| `day[n].newOrders` (event day) | `round(3*0.33)` | `1` |
| `control.numberOfDays` | copied from `template.numberOfDays` | `"40"` |
| `control.status` | PHP literal | `200` |
| `manpower.Inspect.pos` | `"10" / 5` (PHP int division) | `2` |
| `orders[n].day` | loop counter | `1` |

`DrillInit` reproduces the *computed* half exactly (`jsonNumber()` collapses integral
doubles so `round()` output matches, `phpDivide()` returns an int when evenly divisible).
It does NOT reproduce the copied half: those values go through `PhpRows`, which maps
integer columns to JSON numbers.

**22.4 RULED (2026-10-27) — numeric strings are not a parity target.** Production mysqli
returns numeric columns as STRINGS (the notes already rely on this in §11.1, where
`'2' !== 2` is always true), and `PhpRows` maps `int`/`bigint` to JSON numbers, so the
port's JSON carries numbers where production carries `"3"`. The owner's ruling: PHP casts
automatically — `"1"` is usable as `1`, and adding a string yields a string — so the two
forms are interchangeable in this stack and the difference is not worth modelling. The
port keeps `PhpRows` (numbers) as its canonical form; do NOT add string coercion to match
the blobs. Ledger **R5**, ruled.

Scope of the ruling — it is about the WIRE TYPE only. PHP's *strict* comparisons still
have to be reproduced where the PHP relies on them, and the port already does:
`model/` §11.1 (`'2' !== 2` is always true, so `staffs` is always rebuilt) and
`DrillCommon.isStrictlyZero` (`$readMsg !== 0` lets the string `"0"` through). Those are
behaviour, not formatting — they stay as they are.

`1In4` is the same story and is therefore also closed: the dump declares `action.1In4` as
`int` while both real blobs store `"1"`; the port emits the number.

Other INIT facts worth knowing:

- `model.modelContent` is the template blob (`staffs`, `environments`, `recruits`,
  `ordersToComplete`). Its JSON arrays decode to `java.util.List`, not `PhpArray` — a
  `foreach` over them must accept both shapes (`DrillInit.seq()`). Iterating
  `PhpArray.values()` only is a silent no-op that drops every member.
- `staffs[].staffName` is a **staffId**, looked up against `staffprofile_view`. A missing
  row degrades exactly like PHP: `rand(null,null)` -> `max(0, 2|10)`.
- `init.php` sets `state` to `blink` / `halt`, `introduced` 0 and `countTime` false. The
  real blobs show `normal`/1/true **because the client rewrites the row**:
  `DrillPlatform.vue` flips those during the introduction and `action.php:7` then UPDATEs
  the step-0 row with the client's content. INIT's own output is the blink/halt version.
- `select * from template t, drill d` has `duration` (and `templateName`, `modelName`)
  twice. `mysqli_fetch_assoc` keeps the LAST column, so `$template['duration']` is really
  `drill.duration`. `PhpRows`' LinkedHashMap reproduces that by accident — keep it.
- `$pc = $template['numberOfStaff'] / num_rows` divides by the task count. With zero task
  rows PHP produces INF, `json_encode` fails and the whole response becomes an empty body;
  the port skips the manpower block instead of reproducing a crash.

## 23. drill/ mod=ENVIRONMENT — what the event step really does

`environment.php` fires the event the client scheduled for today, then rolls the status
ladder. Five behaviours are easy to get wrong:

**23.1 An UNTARGETED event never runs `cal_affect` at all.** The broadcast is:
`if ((member[cx].staffName != $ens) && ($ens != ''))` — the second half means that when the
event has no `staffName` (the common case) every member is processed by the *impact* loop
and nobody by the *affect* loop. Getting this backwards double-processes every member and
inflates the whole board (the first run of the test suite showed exactly that).

**23.2 The event stack shifts down but index 0 survives.** The loop copies `[v]` to `[v-1]`
for `v` in `1..count-1`, then writes a blank at index `$v` (= count) and decrements
`count`. With a single event nothing is copied, so the arrays keep their old entry and a
blank is appended: `eventName` ends up `["Offshore", ""]` with `count: 0`.

**23.3 `count` must stay an integer.** `$count--` in PHP is int arithmetic; doing it in
double and letting Jackson serialise gives `0.0`, which no blob contains.

**23.4 Writing into a decoded JSON ARRAY needs a PhpArray.** `PhpJson.decodeAssoc` maps JSON
arrays to `java.util.List`, and `PhpArray.child(key)` on a List *replaces* it — so
`$event['description'][1] = ''` would silently drop element 0. `DrillEnvironment
.mutableChild()` converts a List to a PhpArray (same indices) on first write. Any module
that mutates arrays inside a client-supplied blob needs the same care.

**23.5 The response is gzipped in the PHP.** `header("Content-type: text/javascript")` +
`Content-Encoding: gzip` + `gzencode($json)`. Browsers decompress transparently, so the port
answers plain `application/json`: identical after decoding, no hand-rolled gzip, and the
client's `res.status === 200` / `res.json()` path is unaffected. Deliberate wire-level
divergence.

Smaller contract details:

- `remainingMinutes` is `date_diff(now, expireOn)->h * 60 + ->i` as `"%d:%02d"` — the DAY
  component is ignored, so a drill three days out reports only its hour remainder. When the
  ladder lands on 750/900 the field is the string `"0.00"`.
- `$grace` uses `=== 0` on `day[dn+3].evaluateStyle`: a MISSING day makes that comparison
  false, so grace is 4, not 3. The port reproduces that with an `isZero()` that only accepts
  a real number.
- `resignCheck` recycles the slot: staffExp/`staffName` cleared, `workingOn` order state set
  to -1, counters zeroed, `away = rand(resign_embargo_min_days, resign_embargo_max_days)`,
  and the tolerance values restored from `params_int`.
- `setAway` stores `environment.duration` into `member.away` (a duration, not a countdown).
- `drilldetail.drillId` has no FK, so an unknown drill still gets a row and the endpoint
  answers 200 — the 204 branch needs a genuinely unreadable row.

## 24. drill/post.php — the POST verb (query string IS the userId)

`POST /backend/drill/?U123` is how `Drill.vue` gets its home screen: `{user, control, runs}`.
It ignores `mod` completely (read and never used) and keys everything off
`$_SERVER['QUERY_STRING']`, so `ctx.queryString()` is the only correct source — and the
client's URL is `/backend/drill/?<userId>`, with the trailing slash before the `?`.

- The **user object is the JOINED** `backenduser` + `company` row. `companyName` and
  `active` exist in both tables, and `select *` + assoc fetch keeps the LAST one, so the
  company's values win. `PhpRows`' LinkedHashMap reproduces that for free.
- **`runs` is only added when a row exists.** A control with zero drills replies with
  `{user, control}` and no `runs` key at all — not an empty array. The client tolerates it.
- First contact **creates** the control row (`uniqid`, `createdOn = now()`) and one drill per
  configured company slot (`runOne`/`runTwo`/`runThree`), each with `seq` 1..n, `run` naming
  the slot, and `drillStatus` 100. A slot whose `modelId` does not resolve simply produces
  no drill. The control is then re-read so the reply carries the new id.
- The gate is `level = 0 AND backenduser.active = 1 AND company.active = 1`: instructors,
  disabled users, dead companies and unknown ids all get a bodiless **204**.
- `template.duration` is INT in production but DECIMAL in the test fixture, so the insert
  coerces it with `PhpJson.num()` — otherwise H2 refuses to put `"0.00"` into an int column
  while MySQL would have coerced silently.

## 25. The movement maths (cal_works / cal_swap / recal_eff)

**25.1 `cal_swap`'s profilesens query cannot succeed — by design of the schema.** It asks for
`impactClass`, `skillsMin`/`Max`, `moraleMin`/`Max`, `efficiencyMin`/`Max` FROM
`profilesens`. The production dump has none of those columns there (they live in
`profileimpact`), and the query fails on every call: `mysqli_query` returns false, `$res` is
null, and on PHP 7 every `$res[...]` read is 0. Consequences the port reproduces:

- the `skillsAdded != 0` branch computes `x * $seff * 0 + 0 * 0` = **0**, so a swap that
  changes skills never moves efficiency through the impact term;
- the `moraleAdded != 0` branch survives, because it does not multiply by `$res`:
  `round($swapSkills * ($moraleAdded / $moraleValue))`;
- `$seff` (the skillseffect band) is fetched but ends up unused for the same reason.

Verified two ways: `grep "from profilesens" drill/*.php` matches only that one line, and the
dump's `profilesens` DDL is `skillsClass/moraleClass/efficiencyClass` + three sensitivities.
The 2026-08 blob agrees — step 3 carries `半夏子/evaluate-4-negotiate: 0, 0, 0`.

**25.2 The 9-character prefix is `evaluate-`, not `swap-task-`.** `action.php` sets
`$content['members'][$c]['action'] = 'evaluate-' . $task` and then calls
`cal_swap(..., 'swap-task')` — the *type* argument is `swap-task` while the *string* is
`evaluate-<task>`. The hard-coded `substr($swapTask, 9)` strips exactly `evaluate-`. Getting
this wrong makes every swap silently no-op (both task_eff lookups miss, skills land on 0).

**25.3 `intval()` vs `round()`.** `cal_swap` truncates toward zero for the weight
conversion and for `addedAvg` (`intval($added / 3)`), so a negative `added` of -8 gives
addedAvg -2, not -3. `recal_eff` uses `round()` and has **no clamp at all**, so it is the one
place efficiency can go above 100 (or below 0); `cal_swap` clamps 0..100; `cal_impacts`
clamps 0..120. Three different rules in three neighbouring functions — do not unify them.

**25.4 `cal_works` measures work in two places at once.** `member.workAvailable` and
`order.workNeeded` both move, both rounded to 2 decimals. When the order completes, it is
logged under the `$state` argument (captured at call time), `task_work[state-1].worked`
counts up, and the order is nullified (`state = -1`) until that count reaches the task's
`passingRate`. Closing stage 5 stamps `control.newDeal` and, once only, `control.firstDeal`
with the same weekday adjustment used elsewhere.

## 26. commonLib is complete — the last four helpers

All 22 functions of `drill/commonLib.php` are now in `DrillCommon`. The final four:

**26.1 `weekSummary` keeps two kinds of total in one object.** `weekOrder`/`weekValue` are
per-week, while `order`/`value` are CUMULATIVE across the drill (`$odr` never resets). A
deal is attributed to the week of `day[order.log[5].completedDay].week`, and only when the
order is at `state == 5` with `workNeeded == 0`. An order closed without that log entry
lands in week `''` — PHP creates that key, so the port does too rather than skipping it.

**26.2 `setEnvironment` schedules for TOMORROW and has no horizon check.** `$when` is
`dayNumber + 1` unconditionally, so an event set on what the client thinks is the last day
creates `day[numberOfDays + 1]` outside the calendar. `c > 0` attributes the event to that
member's slot; `c == 0` means company-wide (empty staffName/staffExp). Preserved verbatim —
this is reachable from action.php's environment-naming actions.

**26.3 `ana_style` writes into the same array as the style names.** `analysis.style` holds
`total`, the three comparison buckets (`desire_actual`, `desire_intent`, `intent_actual`)
AND one entry per style name. That is why this must stay a `PhpArray`; a typed map would
either drop the buckets or turn the object into an array.

**26.4 `ana_action` decides positive/negative from the member's `added`,** not from the
action: `added > 0` is positive for the parent action, the staff member and (when the action
carries an `actualStyle`) the style trio. The style block is guarded by `strlen($actual)`, so
an empty `actualStyle` leaves all three style counters untouched. `action.actualStyle` was
missing from the test fixture and has been added.

With this, `action.php` (891 lines) and `bossreview.php` (108) are the only PHP left in
`drill/`.

## 27. drill/bossreview.php — the boss's reaction

Ported last so `action.php`'s SELECT_STYLE branch can call it. Five things to know:

**27.1 It appends to `content.log.action` BEFORE the early return.** The
`[actionName, noImpact, managementAction]` triple lands even for a `noImpact` action that
skips the whole review, so the log always has one entry per reviewed step.

**27.2 `SELECT_STYLE` overrides two inputs.** It forces `applyAll = 0` (local copy only —
the action row is untouched) and it makes EVERY named member count as an IMPACT regardless
of the `actioned` flag. For any other phase, a member who did not act becomes an AFFECT
(`added != 0`) or is ignored.

**27.3 `noImpact` is compared loosely against 1**, but `SELECT_STYLE` re-enables the review:
`if ($action['noImpact'] != 1 || $q->action == 'SELECT_STYLE')`.

**27.4 Four percentages, then a range lookup.** Rates are `plus/count*100` as floats; the
PHP splices them into the SQL inside single quotes, so MySQL casts `'50'` back to a number.
The port binds them as numbers — same comparison. Zebra-striped `bossreview` rows whose
`From..To` ranges contain the rates and whose `managementAction` matches the action row.

**27.5 `_e` is appended to the message only when the row wants a comment AND the team's
efficiency actually rose** (`$row['efficiencyChange'] && $effAdd > 0`). `effCount` is
computed and never used. `content.bossRev.descriptions` is what `writeSummary` later copies
into the step's `summary[].bossRev`.

The test fixture gained a `bossreview` table (three rows covering unanimous / mixed /
management-specific cases).

## 28. drill/action.php — the shape of the file (SELECT_STYLE ported)

Three parts, only the middle one differs per move:

1. **Preamble** — rewrite the client's row (`update drilldetail set content=...`), load the
   product's task weights (only the PHP's dead `if (false)` block reads them), read
   params_int, `json_decode` the content, look up `action` by `content.control.action`,
   `initMemberValues`, clear `touched` on every slot.
2. **`switch ($qdata->action)`** — SELECT_STYLE (ported), MORE_ACTIONS (in progress, §29).
   There are only these two branches; `qdata.action` is set by the client from the previous
   step's `control.action`, so anything else is a client bug and gets 501 with the name.
3. **Shared tail** — `weekSummary`, `writeSummary`, clear `actioned`/`actionExp`, mint the
   next `drillDetailId`, `stepNumber++`, `dayNumber++`, `welcome=0`, choose the NEXT action
   from `day[newDay].evaluateStyle`, run the status ladder (600 celebrate / 700 out of days
   / 750 no staff / 900 completed), assign staff to the new day's unclaimed events, then
   update the drill, insert the row and answer.

**28.1 `$prf` is cal_impacts' by-reference `&$res`.** SELECT_STYLE passes `$prf` into
`cal_impacts` for every member and reads `$prf['desiredStyle']` / `$prf['impactClass']`
afterwards — so the style scoring and the boss review are driven by the LAST member's
profile row, not by the action. `DrillCommon.calImpacts` therefore RETURNS the profile row;
do not turn that back into void.

**28.2 The tail advances the day UNCONDITIONALLY** (`$qdata->dayNumber++`), and the next
action is then chosen by the new day: `evaluateStyle` -> SELECT_STYLE, else MORE_ACTIONS with
`control.action`/`control.task` cleared. `daysNeeded` is 0 for SELECT_STYLE, so the
`numberOfDays` adjustment does nothing here (it matters for moves that consume extra days).

**28.3 `writeSummary` runs BEFORE the tail changes `control.action`**, so the summary's
`phase` is the move just played (SELECT_STYLE), not the next one.

**28.4 The environment staff assignment only runs on the non-terminal path.** For each
unclaimed event of the new day it picks a member via `applyAll`/`randomOne`/`lowMorale`/
`lowEfficiency`/`highEfficiency`; a `resignCheck` event with a nominated member is first
given a chance to be called off when their morale beats the recorded criteria (or is 100).

**28.5 Integer columns bound from PHP strings.** `bossreview.applyAll`/`managementAction`
are INT columns and the PHP splices them as quoted strings, so MySQL casts `''` to 0. Binding
`""` makes H2 fail the comparison outright — bind the number (0 for an absent value).

**28.6 Same gzip divergence as environment.php:** the PHP answers `text/javascript` +
`Content-Encoding: gzip`; the port answers plain JSON.

## 29. MORE_ACTIONS — decomposition (recruit block ported)

The branch runs from `case "MORE_ACTIONS":` to the shared tail, ~660 lines. It is laid out
as a sequence of independent passes over the member list, so it ports in this order:

| # | block | lines | state |
|---|---|---|---|
| 1 | `actionType == 'recruits'` — move actioned recruits into free slots | 77–124 | **ported** (`DrillAction.applyRecruits` + `DrillRecruitMathTest`) |
| 2 | `swapTask` — pairwise task swap between actioned members | 126–176 | **ported** (`DrillAction.applySwapTask` + `DrillSwapTaskTest`) |
| 3 | `used.actions[parentAction]++`, then the per-member impact loop (`cal_impacts` / `cal_affect`, `recal_eff`, `cal_works`, history) | 179–~500 | not ported |
| 4 | meet-the-boss / order-stage progression and `setEnvironment` triggers | ~500–700 | not ported |
| 5 | `writeSummary` leftovers before the shared tail at 739 | ~700–738 | not ported |

Notes for whoever picks this up:

- **The recruit block scans exactly ten recruit rows** (`for ($r = 0; $r < 10; $r++)`,
  hard-coded) and fills ONE slot per recruit (`break`). A slot is eligible when it is empty
  AND (`slot.task == control.task` OR the slot has no task at all). Stats come from
  `staffprofile_view`; a missing view row degrades to `rand(0,0)` = 0 rather than erroring.
  The recruit is appended to `content.staffs` so later steps see them as staff, and the
  recruit row itself is blanked (`staffName`/`staffExp`/`actioned`).
- **The swap block mutates in pairs**: it remembers the first actioned member's task, then
  for each subsequent actioned member it temporarily sets both `action` fields to
  `'evaluate-' . otherTask`, calls `cal_swap` on each with type `swap-task`, restores the
  `action` strings, swaps the tasks, and nullifies any order either was working on
  (`state = -1`, `workingOn = 0`).
- `content.used.actions[parentAction]++` counts how often an action GROUP was used (the
  array exists from init as `used.actions = []`).
