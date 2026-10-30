# Frontend image integration

## Contract and access

Use the new media API for uploads and display. The PHP-compatible endpoint is still
available for old clients, but retains its old behavior; do not use it for new work.

- Upload: `POST /backend/media/images`.
- Authentication: `Authorization: Bearer <restkey>`, using the existing login session
  key. Do not put the key in the URL or in the multipart body.
- Multipart field: `file` (not the legacy `upFile`).
- Display: `GET /backend/media/images/{id}`, with **no authentication**.
- Supported new uploads: JPEG and PNG only. Client extensions and MIME declarations
  are not trusted. The backend decodes and re-encodes the pixels, stripping metadata.
- Limits: input and normalized output must each be <= 2 MiB (2,097,152 bytes);
  width and height must each be <= 8192; width * height must be <= 16,000,000.
  Resize large photos before uploading. GIF, SVG, WebP and HEIC uploads are not supported.

**Photos are public to anyone with their URL**, including after the uploading session
expires. Random IDs prevent simple sequential enumeration but are not access control.
Do not upload confidential images under this contract.

## Upload response

Success is `201 Created`, with a `Location` header and this JSON shape:

```json
{
  "id": "09f7ce5d-c351-4bdb-92b0-cf41b7653fbe.png",
  "fileName": "09f7ce5d-c351-4bdb-92b0-cf41b7653fbe.png",
  "url": "/backend/media/images/09f7ce5d-c351-4bdb-92b0-cf41b7653fbe.png",
  "contentType": "image/png",
  "size": 153420,
  "width": 640,
  "height": 480
}
```

`fileName` is an alias of `id` for integration with the existing `staffPhoto` field.
Store the **ID**, not a machine-local path, backend hostname, or temporary blob URL.
`size` is the stored, normalized image size and may differ from the original file.

Uploading stores an image only. It does **not** create/update a staff record or change
the staff RPC response shape. Set the edited staff model's `staffPhoto` to `id`, and
persist it through the staff RPC: HTTP `POST /backend/staff/` with
`{"restkey":"<key>","method":"PUT","qdata":{...}}`. Include `staffId` (`-1` for new
staff), `staffName`, `staffDescription`, `staffSelfIntroduction`, `staffInterview`,
`staffPhoto`, `skillsProfile`, `moraleProfile`, `efficiencyProfile`, `yearsWorked`
and `experience`. Integer fields can be numbers or integer strings. The response is
`200` with a one-row array including the saved ID and profile display expansions;
an unknown update ID returns 204, invalid integer fields/missing qdata return 400.
Existing staff responses still contain `staffPhoto`, not an automatically generated
`photoUrl`. The media upload itself does not save the staff record.

## Vue/browser implementation

The following TypeScript helpers use the browser's built-in `fetch`. They do not use
the legacy JSON/gzip RPC envelope.

```ts
// Empty when /backend is proxied on the frontend origin. Otherwise use the backend
// origin, e.g. https://api.example.com (without a /backend suffix).
const backendOrigin = '';

interface UploadedImage {
  id: string;
  fileName: string;
  url: string;
  contentType: string;
  size: number;
  width: number;
  height: number;
}

function backendUrl(path: string): string {
  return `${backendOrigin.replace(/\/$/, '')}${path}`;
}

export function staffPhotoUrl(id: string | null | undefined): string | null {
  if (!id) return null;
  return backendUrl(`/backend/media/images/${encodeURIComponent(id)}`);
}

export async function uploadStaffPhoto(
  file: File,
  restkey: string,
): Promise<UploadedImage> {
  if (file.size === 0 || file.size > 2 * 1024 * 1024) {
    throw new Error('Select a non-empty image no larger than 2 MiB.');
  }
  const form = new FormData();
  form.append('file', file);

  const response = await fetch(backendUrl('/backend/media/images'), {
    method: 'POST',
    headers: { Authorization: `Bearer ${restkey}` },
    body: form,
  });
  // Do not set Content-Type: the browser must supply the multipart boundary.
  if (!response.ok) {
    const text = await response.text();
    let message = `Image upload failed (${response.status})`;
    try {
      const problem = JSON.parse(text);
      if (typeof problem.detail === 'string') message = problem.detail;
    } catch {
      // A reverse proxy may return HTML/text instead of the API's problem JSON.
      message += '. Please retry or contact support.';
    }
    throw new Error(message);
  }
  return response.json();
}
```

In the existing upload handler, disable the upload control while a request is pending,
show errors visibly, and change `staffPhoto` only after a successful response:

```ts
const uploaded = await uploadStaffPhoto(selectedFile, restkey);
staff.staffPhoto = uploaded.id;
// Now save the edited staff record using the application's staff-save workflow.
```

Display the saved ID with a normal image element (adapt to your Vue version):

```vue
<img
  v-if="staff.staffPhoto"
  :src="staffPhotoUrl(staff.staffPhoto)"
  alt="Staff photo"
/>
```

Use `accept="image/jpeg,image/png"` on the file input as a picker hint; it is not
validation. Show a placeholder for absent images and handle the image element's
`error` event to show a fallback if retrieval fails. Avoid repeatedly retrying a broken
fallback URL.

## Errors

API errors use `application/problem+json`, with `status`, `title` and `detail`.
For example:

```json
{
  "type": "about:blank",
  "title": "Unauthorized",
  "status": 401,
  "detail": "A valid session key is required in Authorization: Bearer <restkey>"
}
```

| Status | Meaning / frontend action |
|---|---|
| 400 | Missing/empty file, corrupt image or excessive pixel dimensions; ask for another image |
| 401 | Missing, invalid or deleted session; require login again |
| 404 | Unknown/unsupported image ID or missing file; show a placeholder |
| 413 | File/request or normalized image too large; resize/compress and retry |
| 415 | Unsupported image contents or request content type |
| 500 | Storage operation failed; show error, do not update the staff photo |
| 503 | Storage not configured or database unavailable; show error/contact administrator |

Proxy/web-server upload limits may reject the request before Java and return a
different error format. Treat every non-2xx response as a failure.

## Existing images and caching

The same GET route accepts existing PHP filenames: 13 lowercase hex characters
followed by `.jpg`, `.jpeg`, `.png` or `.gif` (extension is case-insensitive).
Example: `/backend/media/images/6ae48c53a7e1c.JPG`.
The file must be present in the shared upload directory and recognizable as a supported
image. Existing database values do not need rewriting if they already contain these
filenames. Other naming schemes or full URLs/paths require a separate migration;
do not pass filesystem paths to this API.

New UUID filenames are immutable and publicly cached for one year. Replacing a photo
means uploading a new file and saving its new ID; do not overwrite the old file or
append timestamp query parameters. Legacy images are publicly cached for one hour.
The backend sends `Content-Type`, `Content-Length`, `Last-Modified` and `nosniff`.

No automatic deletion is performed: uploading and abandoning a staff edit can leave
an unreferenced file. Retain old files during migration; add a separate retention
policy once all database references are understood.

## Backend deployment checklist

1. Set `WLWL_UPLOAD_DIR` to the actual persistent image directory. If unset, the API
   falls back to `params.fileLocation`; missing configuration returns 503.
2. The new API creates the directory on upload if needed. Grant the Java process
   read/write access. In Docker mount a persistent volume owned by UID 10001.
3. Copy existing PHP image files into that directory, preserving filenames. Merely
   changing an environment variable does not migrate files.
4. Route `/backend/media/images` and `/backend/media/images/*` to Java, not the old
   PHP handler/static webroot. Do not expose the upload directory independently.
5. Prefer a same-origin reverse proxy (also in Vue development). This backend does
   not enable cross-origin uploads: if you use a separate API origin, explicitly
   configure CORS/preflight for your frontend origin and the Authorization header
   at your trusted proxy before deploying the frontend. Never enable arbitrary
   credentialed origins.
6. Permit multipart requests up to the configured request limit (8 MiB) at the proxy.
   The per-file limit remains 2 MiB.
7. Test login -> upload -> save staff -> reload page -> display photo, including
   invalid sessions, oversized files and missing-image fallback.

The legacy `POST /backend/images/upload.php?<restkey>` is unchanged for compatibility.
It still uses `upFile`, returns its legacy statuses and retains its same-second
timestamp behavior. Only switching the frontend to the new endpoint fixes that flow.
