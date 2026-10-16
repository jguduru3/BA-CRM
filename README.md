# Better CRM

A Business Relationship Workspace for Better Analytics: structured CRM core (leads →
opportunities → companies/contacts), Notion-style custom databases with file/image
attachments, a freeform whiteboard for planning, WhatsApp + email communication, an
AI automation layer (Claude/GPT), role-based permissions, and a full audit trail.

Spring Boot 3 / Java 21 backend, React 18 / TypeScript / Vite frontend, PostgreSQL
(Flyway-migrated), file storage that works locally today and is an Azure Blob
drop-in later.

> **Start here:** [ABOUT.md](ABOUT.md) is the complete guide: every feature, the
> developer documentation (architecture, codebase map, security, APIs, testing, how to
> extend) and the roadmap. Operators: [DEPLOYMENT.md](DEPLOYMENT.md). Integrators:
> [docs/MASTER_API.md](docs/MASTER_API.md).

## Verification status

Everything below was actually run in this environment, not just reviewed — see
`docs/VERIFICATION.md` for the full session log (exact commands, output, and the
bugs that were found and fixed by running things rather than by reading them).

| Layer | Status |
|---|---|
| Backend tests | ✅ `./mvnw verify`: 375 tests (JUnit 5 + MockMvc on H2, every Flyway migration V1–V81 applied), 0 failures; production jar builds |
| PostgreSQL | ⏳ `PostgresMigrationIT` (Testcontainers) runs in CI; skipped where Docker is unavailable |
| End-to-end | ✅ 78-step API walkthrough of every module on a fresh install passes |
| Frontend | ✅ `npm run lint` (ESLint + tsc, zero warnings), `npm test` (81 Vitest tests), `npm run build` |
| UI | ✅ Every route reviewed headlessly at desktop (light + dark) and phone width, zero console errors |
| Docker / Azure services | ⏳ Image builds and live SMTP/IMAP/ACS/Notification Hubs/Meta calls need a real environment (fakes in tests) |

## What's implemented

**Core CRM** — Leads, Companies, Contacts, Opportunities, Tasks, Activities (append-only
timeline), Users/Teams/Departments, full audit log. Every Lead field the product spec
calls for is there: source, company, industry, geography, employee/revenue band,
requirement, pain point, technology used, decision maker, lead score (+ manager
override), assigned salesperson. Same for Activity (call/email/LinkedIn/WhatsApp/
meeting, outcome, next follow-up) and Opportunity (service/product, value, probability,
close date, competitors, current vendor, proposal/negotiation/PO status).

**Permissions** — `PermissionService`: tenant → feature permission → record scope
(own/team/org) → field mask, in that fixed order, with permission-denial audit logging
and delegation support. One feature matrix (`FeaturePermissionTable`) covers every
resource; Support is read-only everywhere. Team-scope sets are memoised per request
and, with optional Redis, cached across replicas for ~60 s.

**Security** — JWT sessions with server-side revocation (token version), account
lockout, forced first-login password change, a password policy that rejects the
~1,000 most common passwords and the user's own name, Bucket4j login rate limits
per IP and per email (shared through Redis when enabled), AES-256-GCM encryption of
stored third-party secrets, no default secrets outside the `local`/`test` profiles,
and uniform JSON error bodies that never leak internals.

**Audit log** — every change is recorded. Super Admin and VP Sales see the whole
organization, Managers see their own team's activity; filterable, paginated and
exportable to CSV (the export is itself audited).

**Master API & webhooks** — one versioned gateway (`/api/master/v1`) exposes every
CRM endpoint to other platforms with scoped API keys (read / read-write, acting as a
chosen user so every permission still applies), a self-describing catalogue, atomic
batches with `{{ref}}` substitution, and HMAC-signed outbound webhooks. See
[docs/MASTER_API.md](docs/MASTER_API.md).

**Workspace tools** — Projects, Todo lists, Calendar, Meetings, Notes, Documents
(Notion-style pages, now with file/image attachments), Custom Databases (typed
properties, table + Kanban board views), and a **Whiteboard** canvas (sticky notes,
text, shapes, images — freeform planning/brainstorming).

**Communication** — WhatsApp Business Cloud API (send/receive, webhooks, templates),
SMTP/IMAP email, push-notification plumbing, Campaigns with a draft → approval →
scheduled → sending → completed lifecycle.

**AI** — Dual-provider (Anthropic Claude / OpenAI), configured per-organization under
Settings → AI Assistant. Lead score / summarize / next-action / draft-message from the
lead panel, with **Send via WhatsApp** closing the Claude → CRM → WhatsApp loop. A
separate Trigger → Action automation engine (`AiAutomation`) runs on-demand or fires
automatically on lead creation / stage change.

**Frontend** — Every module above has a working page: tables, Kanban boards, drawers,
the whiteboard canvas, a database designer, reports with charts, settings, and a
cross-entity trash/restore view. Design is a glass/acrylic system (Fluent-inspired) —
no emoji anywhere, vector icons (lucide-react) throughout.

## Known gaps (deliberately not built — see why)

These need real cloud resources or a scope decision this session couldn't make for you:

- **Azure Service Bus / background job queue** — automation currently fires
  synchronously inline with the triggering request (wrapped in try/catch so a slow
  or failing automation can't break the request). `scheduled` and `webhook` trigger
  types are stored but nothing polls or exposes them yet.
- **Entra ID SSO** — JWT auth only; secrets via env vars (Key Vault references in Azure).
- **Multi-tenant billing/metering** — Phase 6 scope, not started.
- **Rich-text documents** — Document pages are plain text today, not a ProseMirror
  editor; file/image attachments work regardless (they're a separate attachment list,
  not inline content).

## Running it

### Backend

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local   # H2 file DB, zero setup
```

Needs **JDK 21** (the project targets it; this sandbox only had JDK 17, so verification
ran with `-Dmaven.compiler.release=17` as a workaround — the Docker build already uses
`eclipse-temurin:21` so that's unaffected).

First sign-in: a Super Admin (`admin@betteranalytics.in`) is seeded for setup. Set
`BOOTSTRAP_ADMIN_PASSWORD` for the first start to give it a password of your choice;
the API still forces a password change on first sign-in, refusing every other call
until that's done. Details: [DEPLOYMENT.md → First admin login](DEPLOYMENT.md#5-first-admin-login).

Other profiles: `postgres` (local Postgres on `localhost:5432`), `docker` (Postgres via
`DB_HOST`/`DB_PORT`/`DB_NAME`/`DB_USER`/`DB_PASS`, no TLS — what `docker-compose.yml`
uses), `azure` (same vars, `sslmode=require`, for Azure Database for PostgreSQL).

### Frontend

```bash
cd frontend
npm install
npm run dev     # :5173, proxies /api -> localhost:8080
```

### Everything via Docker Compose

```bash
cp .env.example .env        # set DB_PASS, JWT_SECRET, ENCRYPTION_KEY (openssl rand -hex 32)
docker compose up --build   # db (Postgres) + api (internal :8080) + web (:8081, nginx)
```

### Deploying

[DEPLOYMENT.md](DEPLOYMENT.md) covers every environment variable, the Spring profiles,
Azure (Container Apps + PostgreSQL Flexible Server + Blob + optional Redis + App
Insights + Key Vault), secret rotation, backups, scaling and a go-live checklist.

### Master API

```bash
curl -H "X-API-Key: bcrm_…" https://<host>/api/master/v1          # catalogue
curl -H "X-API-Key: bcrm_…" https://<host>/api/master/v1/leads    # any CRM endpoint
```

Keys and webhooks are managed by a Super Admin under **Integrations**. Integrator
guide (auth, scopes, batch, errors, rate limits, webhook signatures in Node and
Python): [docs/MASTER_API.md](docs/MASTER_API.md).

### Install as an app

The web app ships a manifest and a small service worker (static assets only — API
calls are never cached), so browsers offer "Install app" when it is served over HTTPS.
On phones a bottom navigation bar gives one-tap access to Dashboard, Leads,
Opportunities and Tasks.

### Connecting AI (Claude or GPT)

No env var needed — sign in as `SUPER_ADMIN`, open **Settings → AI Assistant**, pick a
provider and paste an API key. That key powers both the per-lead AI actions and any
AI Automations you create. WhatsApp needs its own one-time setup under
**WhatsApp → Accounts** (Meta Cloud API credentials) before "Send via WhatsApp" works.
