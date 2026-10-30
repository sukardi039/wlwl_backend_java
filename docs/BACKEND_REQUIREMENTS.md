# Backend requirements from the frontend

Changes needed in the Java backend (`backend_java`) for the frontend to work correctly.
The frontend has not changed the backend; each item below must be done on the backend side.

## How the frontend calls the backend

Every RPC call is an HTTP `POST` to `/backend/<module>/` with a JSON body:

```json
{ "restkey": "<session key>", "mod": "", "limit": 0, "method": "PUT", "qdata": { } }
```

The body's `method` (`POST`, `PUT`, ...) selects the handler (`BackendGateway.resolveVerb`
→ `ModuleHandler.onPut`, etc.). The frontend reads the response only when the status is
`200`, and expects a JSON body.

## 1. Staff save is not implemented (blocking)

**Resolution (Java backend):** `StaffModule.onPut` now inserts/updates the requested
fields with bound parameters, reads back by generated/existing staff ID, and returns
a one-row JSON array with the same profile expansions as the list handler. Missing
rows return 204. Invalid/missing `qdata` or invalid integer fields return 400 with a
JSON `error`. Existing RPC session behavior is unchanged.

The checked-in production schema defines `staff.staffPhoto` as `varchar(255)`;
the live production database still needs deployment-time confirmation:

```sql
SHOW COLUMNS FROM staff LIKE 'staffPhoto';
```

The original report and required contract are retained below for context.

**Symptom:** Staff screen → edit or add a staff member → save shows
"Save failed. Please sign in again and retry, or contact the administrator".
Nothing is written to the database.

**Cause:** `StaffModule` implements only `onPost`. A `method: "PUT"` request falls through to
the default no-op `ModuleHandler.onPut`, so the gateway answers `200` with an empty body and
performs no update. The PHP backend handled this in `backend/staff/put.php`.

Staff is the only module the frontend writes to (`method: "PUT"`) that has no `onPut` in Java.

**Required:** implement `StaffModule.onPut`, a port of `staff/put.php`.

Request (`views/Staff.vue` → `toUpdate`):

| `qdata` field | Notes |
|---|---|
| `staffId` | `-1` (number) for a new staff member, otherwise the existing `staffId` |
| `staffName`, `staffDescription`, `staffSelfIntroduction`, `staffInterview` | text |
| `staffPhoto` | image ID from `POST /backend/media/images`, e.g. `09f7ce5d-c351-4bdb-92b0-cf41b7653fbe.png` (40 chars), or a legacy filename such as `default.png` |
| `skillsProfile`, `moraleProfile`, `efficiencyProfile` | profile class codes |
| `yearsWorked`, `experience` | integers (may arrive as strings) |
| `skillsExp`, `moraleExp`, `efficiencyExp` | display-only; ignore |

Behaviour:

1. If `staffId == -1`, insert a new row (`staffId` auto-increment) with the fields above.
   Otherwise update those fields `where staffId = ?`.
2. Re-read the saved row and add `skillsExp`, `moraleExp` and `efficiencyExp` from
   `profileclass` (`profileClassCode` → `profileClassDisplayName`), exactly as `onPost` does.
3. Respond `200` with a JSON **array** containing the saved row (the frontend uses
   `NewData[0]`), in the same shape as the `onPost` rows. Respond `204` if the row cannot be read.

Notes:

- Use bound parameters. `put.php` concatenated `qdata` into SQL (SQL injection); do not port that.
- `put.php` re-read by `staffName`. Re-reading by the updated `staffId`, or by the generated key
  after an insert, is preferable: it returns exactly the saved row even when names are duplicated.
- `staffPhoto` is `varchar(255)` in the schema, so 40-character media IDs fit. Confirm that the
  production column has the same width.
- Suggested test: extend the module parity tests with an insert (`staffId: -1`) and an update,
  each followed by a POST list that shows the change.

Until this is deployed, staff edits cannot be saved. The frontend now reports the failure
instead of crashing.

## 2. Invalid session returns an empty 200

`BackendGateway` answers an invalid or expired `restkey` with `200` and an empty body (kept for
PHP parity). The frontend cannot tell this apart from "no data". Not required, but it would help
if this returned `401` with a problem/JSON body. If you change this, coordinate with the frontend
first: many screens treat any non-200 response as "no data".

## 3. Staff photo media API deployment

`docs/FRONTEND_IMAGES.md` → "Backend deployment checklist". In particular:

- `WLWL_UPLOAD_DIR` must point at a persistent volume, writable by the Java process (UID 10001).
- Existing PHP image files must be copied into that directory with their filenames unchanged.
- `/backend/media/images` and `/backend/media/images/*` must route to Java.
- Existing `.jfif` staff photos cannot be served by the media API (only `.jpg`, `.jpeg`, `.png`
  and `.gif` legacy names). They still load from `/images/`, so they must be present there, or be
  re-uploaded as JPEG or PNG.

The frontend's nginx already allows 8 MiB request bodies on `/backend/`.
