# Deploying Better CRM

This is the operator's guide: what to configure, where to run it, how to rotate
secrets, how to back it up and what to check before go-live. For integrators see
[docs/MASTER_API.md](docs/MASTER_API.md).

The system is two containers plus a database:

| Component | Image / build | Port | Health check |
|---|---|---|---|
| **api** — Spring Boot 3, Java 21 | `Dockerfile` (repo root) | 8080 | `GET /actuator/health/liveness`, `/actuator/health/readiness` |
| **web** — nginx serving the React SPA, proxying `/api` to the api | `frontend/Dockerfile` | 8080 | `GET /healthz` |
| **PostgreSQL 16** | Azure Database for PostgreSQL Flexible Server (prod) / `postgres:16-alpine` (compose) | 5432 | — |
| Redis 7 (optional) | Azure Cache for Redis / `redis:7-alpine` (compose profile `redis`) | 6379 | — |

Flyway migrates the schema automatically on start-up. Every replica can start at
once: Flyway takes a database lock, and scheduled jobs run on exactly one replica
via ShedLock.

---

## 1. Environment variables

### Required (the API refuses to start without them, outside the `local`/`test` profiles)

| Variable | Purpose |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `azure` in Azure, `docker` in docker-compose, `postgres` for a local Postgres, `local` (default) for H2. See §2. |
| `DB_HOST`, `DB_PORT` (5432), `DB_NAME`, `DB_USER`, `DB_PASS` | PostgreSQL connection (`docker`, `azure`, `postgres` profiles). |
| `JWT_SECRET` | HMAC key for session tokens. **32+ random bytes** (`openssl rand -hex 32`). Published placeholder values and anything starting with `change_me` are rejected. Changing it signs everyone out (§4). |
| `ENCRYPTION_KEY` | AES-256-GCM key for third-party secrets stored in the database (AI provider keys, WhatsApp tokens, SMTP passwords, webhook signing secrets). **32+ random bytes, different from `JWT_SECRET`.** Keep it stable; rotate only as described in §4. `APP_ENCRYPTION_KEY` is accepted as a legacy name. |

### Optional

| Variable | Default | Purpose |
|---|---|---|
| `ENCRYPTION_KEY_PREVIOUS` | — | Only during an encryption-key rotation: the old key, used for decryption only (§4). Must also be 32+ bytes. |
| `BOOTSTRAP_ADMIN_PASSWORD` | — | First start only: sets the password of the seeded `admin@betteranalytics.in` (§5). Remove after the first sign-in. |
| `JWT_EXPIRATION_MS` | `43200000` (12 h) | Session lifetime. |
| `CORS_ALLOWED_ORIGINS` | empty (same-origin only) | Comma-separated browser origins allowed to call the API cross-origin. Leave empty when the SPA is served by the bundled nginx. |
| `API_DOCS_ENABLED` | `true` | Swagger UI at `/api/docs`, OpenAPI at `/api/docs/openapi.json` (no data). Set `false` to hide. |
| `RATE_LIMIT_PER_IP_PER_MINUTE` | `1200` | Coarse ceiling per client IP across `/api/**`. |
| `RATE_LIMIT_API_KEY_PER_MINUTE` | `600` | Per Master API key. |
| `RATE_LIMIT_LOGIN_PER_IP_PER_MINUTE` | `20` | Sign-in attempts per client IP per minute. |
| `RATE_LIMIT_LOGIN_PER_EMAIL` | `10` | Sign-in attempts per email per 5 minutes (on top of the 5-strikes / 15-minute account lockout). |
| `REDIS_ENABLED` | `false` | `true` shares rate limits and the permission cache across replicas (§7). When `false` no Redis connection is ever attempted and there is no Redis health indicator. |
| `REDIS_URL` | `redis://localhost:6379` | e.g. `rediss://:<access-key>@<name>.redis.cache.windows.net:6380` for Azure Cache for Redis. |
| `PERMISSION_CACHE_TTL_SECONDS` | `60` | How long team-scope permission sets are cached in Redis (evicted immediately on team/user/delegation changes). |
| `APPLICATIONINSIGHTS_CONNECTION_STRING` | — | When set, the Azure Application Insights Java agent is attached at start-up (requests, dependencies, exceptions, logs). |
| `AZURE_STORAGE_CONNECTION_STRING` | — | Store attachments in Azure Blob Storage. Without it they go to `LOCAL_UPLOAD_DIR`. |
| `AZURE_BLOB_CONTAINER` | `bettercrm-docs` | Blob container name. |
| `LOCAL_UPLOAD_DIR` | `./data/uploads` (`/app/data/uploads` in the image) | Local attachment storage when Blob is not configured — mount a volume. |
| `AZURE_KEYVAULT_URI` | — | Reserved; secrets are injected as env vars via Key Vault references (§3). |
| `WHATSAPP_APP_SECRET` | — | Meta App Secret — required to verify inbound WhatsApp webhooks. |
| `WHATSAPP_GRAPH_URL` | `https://graph.facebook.com/v19.0` | Meta Graph API base. |
| `APP_PUBLIC_BASE_URL` | — | Public origin users reach the app on (e.g. `https://crm.example.com`). Used for one-click email unsubscribe links; blank = campaign emails offer a `mailto:` unsubscribe only. |
| `CAMPAIGN_EMAIL_PER_MINUTE` | `60` | Max campaign emails per minute per sending mailbox (per api replica). `0` = unthrottled. Keep below your mail provider's limit. |
| `CAMPAIGN_SMS_PER_MINUTE` | `60` | Max campaign SMS per minute (per api replica). |
| `AZURE_COMMUNICATION_CONNECTION_STRING` | — | Azure Communication Services connection string (`endpoint=https://<res>.communication.azure.com/;accesskey=<base64>`). With `AZURE_SMS_FROM` enables SMS campaigns; blank = SMS disabled (launch returns 409 "SMS is not configured"). |
| `AZURE_SMS_FROM` | — | The ACS phone number SMS are sent from, E.164 (`+18005550100`). |
| `AZURE_SMS_EVENTS_SECRET` | — | Shared secret for the Event Grid webhook `POST /api/sms/events?secret=…` (inbound STOP/START). Blank = endpoint disabled (404). |
| `EMAIL_INBOX_POLL_ENABLED` | `true` | Scheduled IMAP polling of mailboxes with "Watch inbox for replies". |
| `EMAIL_INBOX_POLL_DELAY_MS` | `300000` | Pause between inbox polls (one replica at a time via ShedLock). |
| `EMAIL_INBOX_POLL_BATCH` | `50` | Max new messages read per mailbox per poll (the rest are read on the next poll). |
| `AZURE_NOTIFICATION_HUB_CONNECTION_STRING` | — | Web push via Azure Notification Hubs (DefaultFullSharedAccessSignature connection string). Blank disables push. |
| `AZURE_NOTIFICATION_HUB_NAME` | — | Notification Hub name. |
| `WEBPUSH_VAPID_PUBLIC_KEY` | — | Public VAPID key configured on the hub under *Browser (Web Push)*; served to browsers at runtime via `/api/notifications/push-status`. |
| `OUTBOUND_ALLOW_PRIVATE_NETWORKS` | `false` | Lets webhooks / custom-API automations call private IPs. **Development only.** |
| `DB_POOL_SIZE` | `15` | Hikari pool size per replica. Keep `replicas × pool` below the server's connection limit. |
| `PORT` | `8080` | HTTP port of the api. |

Frontend build-time: `VITE_API_URL` (empty = same origin, the default). Runtime
for the web image: `API_UPSTREAM` (e.g. `http://crm-api` in Azure Container Apps,
`http://api:8080` in compose).

---

## 2. Spring profiles

| Profile | Use it for | Database | Notes |
|---|---|---|---|
| `local` (default) | A developer laptop, zero setup | H2 file DB in `./data` | Weak-secret checks relaxed, dev JWT secret, private outbound allowed, CORS for `localhost:5173`. **Never in production.** |
| `test` | `./mvnw test` only | in-memory H2 | — |
| `postgres` | Developer against a local PostgreSQL | `localhost:5432` | Strict secrets. |
| `docker` | `docker compose` | Postgres container, no TLS | Strict secrets. |
| `azure` | Azure (and any production) | Azure Database for PostgreSQL, `sslmode=require` | Strict secrets. |

---

## 3. Azure deployment (recommended layout)

1. **Resource group + Log Analytics + Application Insights.** Copy the App
   Insights connection string.
2. **Azure Database for PostgreSQL – Flexible Server** (PostgreSQL 16). Create
   database `crm` and a dedicated login (not the admin). Enable *Allow Azure
   services* or private networking with the Container Apps environment. Turn on
   **high availability** and set **backup retention** (7–35 days) for PITR.
3. **Storage account + Blob container** `bettercrm-docs` (private access). Enable
   **blob soft delete** and **container soft delete** (e.g. 14 days), and
   versioning if you need it.
4. **Key Vault** holding `jwt-secret`, `encryption-key`, `db-pass`, the storage
   connection string, `whatsapp-app-secret` and (optionally) `redis-url`. Give
   the Container App's managed identity *Key Vault Secrets User*.
5. **Azure Container Registry** — the pipeline (`azure-pipelines.yml`) builds
   `bettercrm-api` and `bettercrm-web` and pushes them on `main` when
   `ACR_SERVICE_CONNECTION` / `ACR_LOGIN_SERVER` are set.
6. **Container Apps environment** with two apps:
   - `crm-api`: image `bettercrm-api`, **internal** ingress on 8080, min 2
     replicas, 1 vCPU / 2 GiB. Env: `SPRING_PROFILES_ACTIVE=azure`, `DB_*`,
     secrets as *Key Vault references* → `JWT_SECRET`, `ENCRYPTION_KEY`,
     `DB_PASS`, `AZURE_STORAGE_CONNECTION_STRING`, … plus
     `APPLICATIONINSIGHTS_CONNECTION_STRING`. Probes: liveness
     `/actuator/health/liveness`, readiness `/actuator/health/readiness`,
     startup probe on readiness with a 120 s budget (first boot runs migrations).
   - `crm-web`: image `bettercrm-web`, **external** ingress on 8080, env
     `API_UPSTREAM=http://crm-api`. Probe `/healthz`. Custom domain + managed
     certificate.
7. **Optional Azure Cache for Redis** (Basic C0 is enough to start). Set
   `REDIS_ENABLED=true` and `REDIS_URL=rediss://:<key>@<name>.redis.cache.windows.net:6380`
   (as a Key Vault reference). Do this as soon as `crm-api` runs more than one
   replica, so rate limits are shared.
8. First deploy: set `BOOTSTRAP_ADMIN_PASSWORD` (§5), sign in, then remove it.

Health endpoints expose only `{"status":"UP"}` — no components, no details
(`management.endpoint.health.show-details=never`, `show-components=never`); only
`health` and `info` are exposed over HTTP.

---

## 3a. Messaging channels (email, SMS)

**Email campaigns and sequences** send through mailboxes connected under
*Settings → Email accounts* (SMTP; the password is encrypted with `ENCRYPTION_KEY`).
Every campaign email is plain text with a `List-Unsubscribe` header and an
unsubscribe line; set `APP_PUBLIC_BASE_URL` so the header carries a signed
one-click link (`/api/email/unsubscribe`, public, RFC 8058) — the web container
must proxy `/api` publicly (it does by default). Opt-outs are stored per
organization in `email_consent` and honoured by campaigns and sequences.
Use a mailbox / provider meant for bulk mail and set `CAMPAIGN_EMAIL_PER_MINUTE`
to its limit (Microsoft 365: ~30/min per mailbox; Gmail Workspace: ~2000/day).

**Inbox watching**: tick *Watch inbox for replies* on a mailbox (needs an IMAP host).
Every `EMAIL_INBOX_POLL_DELAY_MS` the api reads new INBOX messages (read-only,
UID-based): replies stop sequences and are logged as email activities on the
lead/contact, bounces skip later sequence emails, "unsubscribe" replies opt the
sender out. The first poll only records the current position (no history replay).

**SMS (Azure Communication Services)**:
1. Create a Communication Services resource, buy/verify a phone number with SMS
   capability (toll-free numbers need verification before sending in the US).
2. Set `AZURE_COMMUNICATION_CONNECTION_STRING` (resource → Keys) and `AZURE_SMS_FROM`
   as Key Vault-backed secrets. The Admin console *System* tab shows "SMS … Configured".
3. Inbound STOP/START: ACS delivers inbound SMS only through **Event Grid**. Create an
   Event Grid subscription on the ACS resource for `Microsoft.Communication.SMSReceived`
   with a Webhook endpoint `https://<public host>/api/sms/events?secret=<AZURE_SMS_EVENTS_SECRET>`
   (Event Grid schema). The subscription handshake is answered automatically. Without
   it, STOP replies are not seen and opt-outs must be recorded manually
   (they share the WhatsApp consent store, `POST /api/whatsapp/consent`).

---

## 4. Rotating secrets

### `JWT_SECRET` (signs every session)

1. Generate a new value: `openssl rand -hex 32`.
2. Update the Key Vault secret / env var and restart (or create a new revision
   of) every `crm-api` replica.
3. **Every existing session ends** — all users must sign in again; Master API
   keys are unaffected (they are not JWTs).

Rotate immediately if the secret may have leaked. To sign out one user only, use
"sign out everywhere" (`POST /api/auth/logout-all`) or an admin password reset —
both bump that user's token version.

### `ENCRYPTION_KEY` (encrypts stored third-party secrets)

Stored values look like `enc:v1:<base64>`. Rotation is zero-downtime:

1. Generate a new key (`openssl rand -hex 32`).
2. Set `ENCRYPTION_KEY_PREVIOUS` = the **current** key and `ENCRYPTION_KEY` =
   the **new** key. Restart all replicas. Reads try the new key, then the
   previous one; every write uses the new key.
3. Nothing to run by hand: while `ENCRYPTION_KEY_PREVIOUS` is set, every
   start-up re-encrypts all stored secrets that are still under the previous key
   (`SecretReencryptionRunner`, using the same table/column list as the
   `V50__Encrypt_plaintext_secrets` Java migration). The log line
   `re-encrypted N stored secret(s)` confirms it; a second restart reports 0.
4. Once every replica runs with the new key and the log shows 0, remove
   `ENCRYPTION_KEY_PREVIOUS` and restart.

Never change `ENCRYPTION_KEY` without step 2: values encrypted under an unknown
key read back as "not configured" and must be re-entered.

Plaintext values left over from older versions are encrypted automatically by
the Flyway Java migration **V50** on the first start of this version (it skips
anything already starting with `enc:v1:` and is safe to re-run).

---

## 5. First admin login

V1 seeds one Super Admin, **`admin@betteranalytics.in`**, with a password hash
that is public in this repository. It can never be used as-is in production:

1. Set `BOOTSTRAP_ADMIN_PASSWORD` to a strong one-time password (10+ chars,
   letters and digits, not a common password, not containing "admin") for the
   first start. `BootstrapAdminRunner` replaces the seed hash with it — only
   while the account still has the original seed hash, so it never overwrites
   a password someone has already set, and it is a no-op on later restarts.
   The event is written to the audit log.
2. Sign in with it. The API forces a password change (`mustChangePassword`)
   and refuses everything else until it is done.
3. Remove `BOOTSTRAP_ADMIN_PASSWORD` from the environment.
4. Create real named Super Admin accounts and deactivate or rename the seed
   account if you prefer.

If the variable is not set the seed account keeps the forced-change gate, but
the first person to sign in with the public password would own the tenant — so
always set it, or rotate the password yourself before exposing the app.

---

## 6. Backup and restore

- **Database** — Azure Flexible Server keeps automatic backups with
  point-in-time restore (PITR) for the configured retention. Restore creates a
  *new* server: point `DB_HOST` at it and restart. For portable dumps:
  `pg_dump --format=custom --no-owner -h <host> -U <user> crm > crm.dump` and
  `pg_restore --no-owner --clean --if-exists -d crm crm.dump`.
  Test a restore at least once before go-live.
- **The `ENCRYPTION_KEY` is part of the backup.** A database restored without
  the key it was written with cannot decrypt the stored third-party secrets.
  Keep old keys in Key Vault (disabled versions) for your backup retention.
- **Attachments** — Azure Blob with soft delete (and optionally versioning /
  point-in-time restore for block blobs). With local storage, back up the
  `api_uploads` volume together with the database dump.
- **Audit log** — `audit_events` is append-only; export it periodically from the
  Audit Log page (CSV) if you need long-term archiving outside the database.

---

## 7. Scaling notes

- `crm-api` is stateless (JWT sessions) and scales horizontally.
- **Scheduled jobs** (reminders, campaign sends, …) carry `@SchedulerLock` and
  run on exactly one replica per tick (ShedLock, `shedlock` table, DB clock).
- **Rate limits** are in-memory per replica by default (Bucket4j), so N replicas
  allow up to N× the configured rate. Enable Redis (`REDIS_ENABLED=true`) to
  share them — Redis counters are atomic and fail open to per-replica limits if
  Redis is unreachable.
- **Permission cache** — with Redis on, team-scope sets (who reports to whom,
  delegations) are cached ~60 s and evicted for the whole organization whenever
  a team, user or delegation changes. Without Redis they are computed per request.
- **Database connections** — `replicas × DB_POOL_SIZE` must stay below the
  server's `max_connections` (minus admin headroom).
- Outbound webhooks are delivered from the replica that handled the change,
  after commit, with retries at 10 s / 1 min / 5 min.

---

## 8. Master API and webhooks (for integrators)

Other systems integrate through one versioned gateway, `/api/master/v1`:

- Create API keys and webhooks as a Super Admin under **Integrations**.
- Keys are shown once, stored only as SHA-256 hashes, can be read-only or
  read/write, act as a chosen user (so all permissions apply), expire optionally,
  and work **only** under `/api/master/**`.
- Webhooks are HMAC-SHA256-signed; the signing secret is stored encrypted.

Full guide, with signature verification code: [docs/MASTER_API.md](docs/MASTER_API.md).

---

## 9. Go-live checklist

- [ ] `SPRING_PROFILES_ACTIVE=azure` (or `docker`) — never `local`.
- [ ] `JWT_SECRET` and `ENCRYPTION_KEY` are fresh 32-byte random values, different from each other, stored in Key Vault, not in the repo or pipeline YAML.
- [ ] Backups: PITR retention set, a test restore done; blob soft delete on; old encryption keys retained.
- [ ] `BOOTSTRAP_ADMIN_PASSWORD` used for the first sign-in, then **removed**; the seed admin has changed its password.
- [ ] Named Super Admin accounts created; roles and reporting lines (Teams) set up.
- [ ] `CORS_ALLOWED_ORIGINS` empty unless a separate browser origin really needs it.
- [ ] `OUTBOUND_ALLOW_PRIVATE_NETWORKS` is `false`.
- [ ] `WHATSAPP_APP_SECRET` set if WhatsApp is used.
- [ ] `APP_PUBLIC_BASE_URL` set to the public origin (one-click email unsubscribe); a test campaign email shows a working unsubscribe link.
- [ ] `CAMPAIGN_EMAIL_PER_MINUTE` at or below the mail provider's sending limit.
- [ ] SMS used → `AZURE_COMMUNICATION_CONNECTION_STRING` + `AZURE_SMS_FROM` set and the Event Grid `SMSReceived` subscription to `/api/sms/events?secret=…` created.
- [ ] HTTPS only at the edge (Container Apps ingress / custom domain with a managed certificate).
- [ ] `crm-api` has internal ingress only; Postgres and Redis are not publicly reachable.
- [ ] Liveness/readiness probes configured; Application Insights receiving data.
- [ ] More than one api replica → `REDIS_ENABLED=true`.
- [ ] `API_DOCS_ENABLED` decided (the spec contains no data, but you may hide it).
- [ ] Audit Log page reviewed; a CSV export works.
- [ ] Installed-app check: the site is served over HTTPS so browsers offer "Install app".
