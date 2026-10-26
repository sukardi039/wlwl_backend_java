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
