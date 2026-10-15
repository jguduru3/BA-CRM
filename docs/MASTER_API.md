# Better CRM Master API — integrator guide

The Master API is the single, versioned entry point other platforms use to read
and write Better CRM data and to receive change notifications.

```
Base URL   https://<your-crm-host>/api/master/v1
Format     JSON (UTF-8) in and out
Auth       API key (X-API-Key) — or a signed-in user's bearer token
Spec       GET /api/master/v1            (live catalogue of every operation)
           GET /api/docs/openapi.json     (OpenAPI 3 document for /api/**)
```

Every Master API call is executed **as the API key's acting user**, through the
same controllers the web app uses. Tenant isolation, role permissions, record
scope (own / team / org), field masking, validation, the audit log and webhooks
therefore behave exactly as they do in the UI — there is no second, looser
implementation of any rule.

---

## 1. Authentication

### API keys

A Super Admin creates keys under **Integrations → API keys**:

| Setting | Meaning |
|---|---|
| Name | Free text, shown in the audit log (`[master-api]` user agent). |
| Scope | `read` — GET only. `read_write` — all methods. |
| Acting user | The CRM user the key acts as. Pick a dedicated integration user with exactly the role it needs; the key can never do more than that user. |
| Expiry | Optional, 1–3650 days. |

The key (`bcrm_` + 40 characters) is shown **once**; only its SHA-256 hash is
stored. Send it in either header:

```http
X-API-Key: bcrm_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
Authorization: Bearer bcrm_xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Rules:

- Keys are accepted **only** under `/api/master/**`. The same key sent to
  `/api/leads` gets `401` — the raw API is for the web app's user sessions.
- A revoked, expired or unknown key, or a key whose acting user was deactivated,
  gets `401 INVALID_API_KEY`.
- A `read` key gets `403` for any non-GET call (including a batch containing one).
- Rotate by creating a new key, switching your integration, then revoking the old one.

`GET /api/master/v1/me` tells you who the credential acts as:

```json
{"organizationId": 1, "actingUser": {"id": 7, "name": "Integration bot", "email": "...", "role": "VP_SALES"},
 "credential": "api_key", "apiKey": {"id": 3, "name": "ERP sync", "prefix": "bcrm_AbCdEfG", "scope": "read_write"},
 "scope": "read_write"}
```

### User tokens

A signed-in user's JWT (`Authorization: Bearer <jwt>`) is also accepted, with
that user's permissions — this is what the in-app API console uses.

---

## 2. The catalogue

`GET /api/master/v1` returns a self-describing catalogue, generated from the
running server so it is always current:

```json
{
  "name": "Better CRM Master API", "version": "1", "baseUrl": "/api/master/v1",
  "webhookEvents": ["lead.created", "..."],
  "operationCount": 212,
  "resources": {
    "leads": [
      {"method": "GET",  "path": "/api/master/v1/leads",      "operation": "Lead.list",   "apiKeyAllowed": true, "requiresWriteScope": false},
      {"method": "POST", "path": "/api/master/v1/leads",      "operation": "Lead.create", "apiKeyAllowed": true, "requiresWriteScope": true},
      {"method": "GET",  "path": "/api/master/v1/leads/{id}", "operation": "Lead.get",    "apiKeyAllowed": true, "requiresWriteScope": false}
    ],
    "companies": ["..."], "contacts": ["..."], "opportunities": ["..."], "tasks": ["..."]
  }
}
```

Request and response bodies are the same as the web app's; see
`/api/docs/openapi.json` (or Swagger UI at `/api/docs`) for schemas.

---

## 3. Calling an endpoint (proxy)

Any catalogue path works under the gateway. `ANY /api/master/v1/{path}` runs
`/api/{path}` with the original method, query string and body:

```bash
curl -s https://crm.example.com/api/master/v1/leads?limit=50 \
  -H "X-API-Key: $BCRM_KEY"

curl -s -X POST https://crm.example.com/api/master/v1/companies \
  -H "X-API-Key: $BCRM_KEY" -H "Content-Type: application/json" \
  -d '{"name": "Acme Pvt Ltd", "industry": "Manufacturing"}'
```

Responses carry `X-Master-Api-Version: 1` and the request's `X-Request-Id`.

**Never available through the gateway:** `/auth/**` (sessions), `/master/**`,
`/integrations/**` (so a leaked key can't mint keys or redirect webhooks),
`/whatsapp/webhook`, `/health`.

**Additionally blocked for API keys** (user tokens may still call them): password
resets (`/users/{id}/reset-password`), `/settings/**` (AI provider keys),
`/whatsapp/accounts/**` and `/ai-automations/email-accounts/**` — anything that
reads or changes third-party credentials.

Paths containing `.`/`..` segments, encoded slashes or other unusual characters
are rejected with `400`.

---

## 4. Batch

`POST /api/master/v1/batch` runs up to **50** operations in one call:

```json
{
  "atomic": true,
  "stopOnError": false,
  "operations": [
    {"id": "co",  "method": "POST", "path": "/companies", "body": {"name": "Acme Pvt Ltd"}},
    {"id": "ct",  "method": "POST", "path": "/contacts",
     "body": {"name": "Asha Rao", "companyId": "{{co.body.id}}", "notes": "Joined via {{co.body.name}}"}},
    {"id": "get", "method": "GET",  "path": "/contacts/{{ct.body.id}}"},
    {"id": "q",   "method": "GET",  "path": "/leads", "query": {"companyId": "{{co.body.id}}", "limit": 10}}
  ]
}
```

- **`id`** names an operation (default `op0`, `op1`, …); ids must be unique.
- **References** — `{{opId.body.field.sub}}` inserts a value from an earlier
  operation's response. Inside a JSON body, a string that is *only* a reference
  (`"{{co.body.id}}"`) is replaced by the raw JSON value (a number stays a
  number); a reference inside a longer string is inserted as text. References
  work in `path`, `query` and `body`. A reference to an unknown/later operation
  or a missing field fails that operation with `400`.
- **`atomic: true`** — all operations run in one database transaction. If any
  returns 4xx/5xx, *everything* is rolled back, processing stops, the response
  status is `409` and `rolledBack: true`. Webhooks for rolled-back changes are
  never sent.
- **`stopOnError: true`** (non-atomic) — stop at the first failure; earlier
  successes stand.
- Otherwise every operation runs and stands alone; the response status is `200`
  and `success` tells you whether all of them succeeded.

Response:

```json
{"success": true, "atomic": true, "rolledBack": false, "executed": 4,
 "results": [{"id": "co", "status": 201, "body": {"id": 91, "name": "Acme Pvt Ltd"}},
             {"id": "ct", "status": 201, "body": {"id": 412, "companyId": 91}}]}
```

A `read` key cannot submit a batch that contains a write.

---

## 5. Errors

Every error has the same shape:

```json
{"error": "Human-readable message", "code": "FORBIDDEN", "requestId": "3f9c0c6b2a4e4f1e9d0f0d6a3b1c2e4f"}
```

| Status | `code` (examples) | Meaning |
|---|---|---|
| 400 | `BAD_REQUEST`, `VALIDATION_FAILED`, `MALFORMED_BODY`, `REQUEST_REJECTED` | Invalid input or malformed URL. |
| 401 | `INVALID_API_KEY`, `UNAUTHENTICATED` | Missing/invalid/revoked/expired credential. |
| 403 | `FORBIDDEN` | The acting user's role or record scope doesn't allow it, the key is read-only, or the endpoint is blocked. |
| 404 | `NOT_FOUND` | Doesn't exist **or belongs to another organization** (the two are indistinguishable). |
| 409 | `CONFLICT`, `STALE_UPDATE` | Business rule / duplicate / concurrent edit; failed atomic batch. |
| 413 | `FILE_TOO_LARGE` | Upload over 25 MB. |
| 429 | `RATE_LIMITED` | Slow down; see `Retry-After`. |
| 500 | `INTERNAL_ERROR` | Our fault. Quote the `requestId`. |

Send your own `X-Request-Id` (8–64 chars of `[A-Za-z0-9._-]`) to correlate logs
end to end; it is echoed on every response.

---

## 6. Rate limits

- Per API key: `RATE_LIMIT_API_KEY_PER_MINUTE` requests per minute (default 600).
- Per client IP across the API: default 1200 per minute.
- Every key response carries `X-RateLimit-Limit` and `X-RateLimit-Remaining`;
  a `429` carries `Retry-After` (seconds). Back off and retry after that.
- With Redis enabled on the server, limits are shared across all replicas.

---

## 7. Webhooks

A Super Admin registers endpoints under **Integrations → Webhooks** (name, HTTPS
URL, events). The signing secret (`whsec_…`) is shown once at creation and on
"Rotate secret"; it is stored encrypted.

### Events

| Event | Fired when |
|---|---|
| `lead.created` / `lead.updated` / `lead.deleted` | Lead changes |
| `opportunity.created` / `.updated` / `.deleted` | Opportunity changes |
| `company.created` / `.updated` / `.deleted` | Company changes |
| `contact.created` / `.updated` / `.deleted` | Contact changes |
| `task.created` / `.updated` / `.deleted` | Task changes |
| `activity.created` | An activity is logged (the timeline is append-only) |
| `note.*`, `meeting.*`, `project.*`, `campaign.*`, `document.*` | created / updated / deleted |
| `user.created` / `user.updated` | User management (passwords are never included) |
| `webhook.test` | "Send test event" button |

Subscribe to exact names, a family (`lead.*`) or everything (`*`).
`GET /api/master/v1/events` lists the current catalogue.

### Delivery

```http
POST /your/endpoint
Content-Type: application/json
User-Agent: BetterCRM-Webhooks/1.0
X-BetterCRM-Event: lead.updated
X-BetterCRM-Delivery: 0b6f0a51-6a3a-4c4e-9d0c-0d1f7a1e2b3c
X-BetterCRM-Timestamp: 1767225600
X-BetterCRM-Signature: sha256=5d2c…

{"id": "evt_6c1d…", "event": "lead.updated", "createdAt": "2026-01-01T00:00:00Z",
 "organizationId": 1,
 "data": {"id": 42, "name": "Acme lead", "actorId": 7, "record": {…after…}, "previous": {…before…}}}
```

- Sent only **after the change commits**.
- Respond with any `2xx` within **10 seconds**. Otherwise it is retried after
  10 s, 1 min and 5 min (same `X-BetterCRM-Delivery` id — use it to de-duplicate).
- After 20 consecutive failures the subscription is disabled; re-enable it in
  the UI. Every attempt is visible under the webhook's deliveries.
- Redirects are never followed, and private/loopback addresses are refused.

### Verifying the signature

`signature = "sha256=" + hex(HMAC_SHA256(secret, timestamp + "." + rawBody))`

Always verify against the **raw** request body (before JSON parsing), compare in
constant time, and reject timestamps older than ~5 minutes to stop replays.

**Node.js (Express)**

```js
import crypto from "node:crypto";
import express from "express";

const SECRET = process.env.BCRM_WEBHOOK_SECRET; // whsec_…
const app = express();

app.post("/bettercrm", express.raw({ type: "application/json" }), (req, res) => {
  const ts = req.get("X-BetterCRM-Timestamp");
  const sig = req.get("X-BetterCRM-Signature") || "";
  if (!ts || Math.abs(Date.now() / 1000 - Number(ts)) > 300) return res.sendStatus(400);

  const expected = "sha256=" + crypto.createHmac("sha256", SECRET)
    .update(`${ts}.${req.body.toString("utf8")}`).digest("hex");
  const ok = sig.length === expected.length &&
    crypto.timingSafeEqual(Buffer.from(sig), Buffer.from(expected));
  if (!ok) return res.sendStatus(401);

  const event = JSON.parse(req.body.toString("utf8"));
  // de-duplicate on req.get("X-BetterCRM-Delivery"), then process asynchronously
  res.sendStatus(204);
});
```

**Python (Flask)**

```python
import hashlib, hmac, os, time
from flask import Flask, request, abort

SECRET = os.environ["BCRM_WEBHOOK_SECRET"].encode()  # whsec_…
app = Flask(__name__)

@app.post("/bettercrm")
def bettercrm():
    ts = request.headers.get("X-BetterCRM-Timestamp", "")
    sig = request.headers.get("X-BetterCRM-Signature", "")
    if not ts.isdigit() or abs(time.time() - int(ts)) > 300:
        abort(400)
    raw = request.get_data()  # raw bytes, before parsing
    expected = "sha256=" + hmac.new(SECRET, ts.encode() + b"." + raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(sig, expected):
        abort(401)
    event = request.get_json()
    # de-duplicate on request.headers["X-BetterCRM-Delivery"], then process asynchronously
    return "", 204
```

---

## 8. Checklist for a new integration

1. Create a dedicated CRM user with the smallest role that covers the job.
2. Create a key for it — `read` unless you must write — with an expiry.
3. Read `GET /api/master/v1` to discover endpoints; test with `GET /me`.
4. Use `atomic` batches for multi-step writes that must succeed together.
5. Register a webhook instead of polling; verify signatures and de-duplicate on
   the delivery id.
6. Log the `X-Request-Id` of every failed call.
