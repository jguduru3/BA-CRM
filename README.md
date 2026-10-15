# Better CRM

A Business Relationship Workspace for Better Analytics: structured CRM core (leads →
opportunities → companies/contacts), Notion-style custom databases with file/image
attachments, a freeform whiteboard for planning, WhatsApp + email communication, an
AI automation layer (Claude/GPT), role-based permissions, and a full audit trail.

Spring Boot 3 / Java 21 backend, React 18 / TypeScript / Vite frontend, PostgreSQL
(Flyway-migrated), file storage that works locally today and is an Azure Blob
drop-in later.

## Verification status

Everything below was actually run in this environment, not just reviewed — see
`docs/VERIFICATION.md` for the full session log (exact commands, output, and the
bugs that were found and fixed by running things rather than by reading them).

| Layer | Status |
|---|---|
| Backend compiles | ✅ `mvn compile` clean (157 Java files, zero errors) |
| Backend boots + migrates | ✅ All 12 Flyway migrations apply; app reaches `Started CrmApplication` |
| Backend endpoints | ✅ Smoke-tested live: auth, leads, contacts, whiteboards, attachments, reports, automations |
| Frontend typechecks | ✅ `tsc --noEmit` clean across ~65 files |
| Frontend builds | ✅ `vite build` succeeds, all routes code-split |
| Docker build | ⚠️ Dockerfiles/compose written and reviewed, **not executed** — no Docker daemon in this sandbox |
| Real PostgreSQL | ⚠️ Not reachable in this sandbox — verified instead against H2 in PostgreSQL-compatibility mode, which caught and fixed 3 real cross-engine SQL bugs (see below) |

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
and delegation support. Computed live per-request, not Redis-cached (see Known gaps).

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

- **Redis-cached permissions** — `PermissionService` is correct but recomputes per
  request instead of caching in Redis. Add when request volume justifies it.
- **Azure Service Bus / background job queue** — automation currently fires
  synchronously inline with the triggering request (wrapped in try/catch so a slow
  or failing automation can't break the request). `scheduled` and `webhook` trigger
  types are stored but nothing polls or exposes them yet.
- **Azure Key Vault, Entra ID SSO** — JWT auth only; secrets via env vars.
- **Multi-tenant billing/metering, public developer API, webhooks-out** — Phase 6
  scope, not started.
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

First sign-in: a Super Admin (`admin@betteranalytics.in`) is seeded for setup. Its
initial password is handed over out of band (see DEPLOYMENT.md), and the API forces a
password change on first sign-in, refusing every other call until that's done.

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
docker compose up --build   # db (Postgres) + api (:8080) + web (:5173, nginx)
```

### Connecting AI (Claude or GPT)

No env var needed — sign in as `SUPER_ADMIN`, open **Settings → AI Assistant**, pick a
provider and paste an API key. That key powers both the per-lead AI actions and any
AI Automations you create. WhatsApp needs its own one-time setup under
**WhatsApp → Accounts** (Meta Cloud API credentials) before "Send via WhatsApp" works.
