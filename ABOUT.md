# About Better CRM

Better CRM is the customer-relationship workspace for **Better Analytics**. It brings
the sales pipeline, accounts, day-to-day execution, knowledge tools, multi-channel
engagement and integrations into one secure, multi-tenant application.

This document has three parts:

1. **[Product guide](#part-1--product-guide)**: every module, what it does and how to use it.
2. **[Developer documentation](#part-2--developer-documentation)**: architecture, codebase
   map, security model, data model, APIs, testing, deployment and how to extend the system.
3. **[Future expansion](#part-3--future-expansion)**: the roadmap, with what's deferred and why.

Related documents:

| Document | Use it for |
|---|---|
| [README.md](README.md) | Quick start and a short feature overview |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Every environment variable, Azure layout, secret rotation, backups, go-live checklist |
| [docs/MASTER_API.md](docs/MASTER_API.md) | Integrator guide: API keys, the Master API, batch, webhooks, idempotency |
| [docs/ADVANCED_FEATURES_RESEARCH.md](docs/ADVANCED_FEATURES_RESEARCH.md) | Sourced research behind the forecasting, deal-health, duplicates and sequences features |
| [docs/VERIFICATION.md](docs/VERIFICATION.md) | Exactly what has been tested, and what still needs a real environment |

---

# Part 1 — Product guide

## 1.1 Who it's for: roles

Every request is checked on the server. The interface only hides what you can't use;
it never enforces access on its own.

| Role | What they see | What they can change |
|---|---|---|
| **Super Admin** | Everything in the organization | Everything, including users, settings, integrations and the admin console |
| **VP Sales** | The whole organization's CRM data | CRM data, campaigns (approve / reject), teams |
| **Manager** | Their team: direct reports, people on teams they lead, and active delegations | Their team's records; can reassign within the team, override lead scores, set quotas |
| **Executive** | Their own records (plus the unassigned lead pool) | Their own records |
| **Inside Sales** | Their own leads; read-only view of deals they sourced, with no values | Their own leads |
| **Field Sales** | Their own records; can claim pool leads in their territory | Their own records (not a lead's geography) |
| **Support** | Read-only, organization-wide | Nothing, apart from marking their own notifications as read |

Some fields are hidden depending on role:
- Inside Sales never see a deal's value, probability or PO status.
- Once a lead is handed off, Inside Sales no longer see its contact details.
- On the activity timeline, people outside management see only the metadata of other people's entries.

## 1.2 Getting started

1. **First sign-in.** The seeded Super Admin (`admin@betteranalytics.in`) signs in with a
   one-time bootstrap password set by the operator (`BOOTSTRAP_ADMIN_PASSWORD`). Nothing
   else works until you choose your own password.
2. **Add your people.** Go to **Admin console → Users → Add user**. Pick each person's role and,
   for Field Sales, their territory. Everyone you add sets their own password at first sign-in.
3. **Build the structure.** Use **Teams** for rosters and team leaders (these drive Manager visibility)
   and **Departments** for your org chart.
4. **Configure your sales process.** From **Admin console → Configuration**:
   - pipeline stage probabilities and forecast categories;
   - lead routing;
   - duplicate rules;
   - contact categories;
   - the AI assistant;
   - email accounts.
5. **Connect channels (optional).**
   - WhatsApp: **WhatsApp → Accounts**.
   - Email: **Settings → Email accounts**.
   - SMS and push: environment variables, set by the operator.
   - Other platforms: **Integrations** (API keys and webhooks).

Everywhere in the app:
- **⌘K / Ctrl+K** opens global search and jump-to.
- The sun / moon / monitor buttons switch light, dark or system theme.
- On phones, a bottom tab bar gives quick access to Dashboard, Leads, Deals, Tasks and More.

## 1.3 Pipeline

### Dashboard
Shows active leads, open pipeline value, won value and overdue tasks, all scoped to what you can see.
Below the tiles:
- pipeline by stage;
- lead sources;
- the lead funnel;
- the team leaderboard (Manager and above).

### Leads
- **Views:** a sortable, paginated table; a pipeline board (Kanban grouped by stage, source,
  type or category); and a **Pool** of unassigned leads.
- **Claim:** a rep can claim a pool lead. Claiming is atomic, so two people can never get the same lead.
- **Routing:** when switched on, new unowned leads are assigned round-robin across a team.
  Territories are respected for Field Sales.
- **Convert to deal:** creates an opportunity and copies over the company, the contact (an existing
  one is matched by email, otherwise one is created) and the value. Converting is safe to repeat:
  a second click returns the same deal.
- **Score override:** Managers and above can override the lead score, but must give a reason.
  The reason is shown on the lead and recorded in the audit log.
- **Follow-ups:** each lead has a next follow-up date and a count of how many times it was
  rescheduled. The owner is notified once when it becomes overdue.
- **AI Assist:** Score, Summarize, Next best action and Draft message. A drafted message can be sent on WhatsApp.
- **Record page and files:** every lead has a block-editor page (headings, text, lists, to-dos,
  dividers) and an attachments panel.
- **Sequences:** enroll the lead in a multi-step email / WhatsApp / task cadence.

### Opportunities (deals)
- **Views:** the board (Qualification → Proposal → Negotiation → Verbal Commit → Won / Lost) or a table.
- **Probabilities:** each stage has a default probability, set per organization. A rep can override it per deal.
- **Closing:** moving a deal to Won or Lost requires a reason, and the close date is recorded.
- **Forecast category:** each stage maps to a category (Pipeline, Best case, Commit, Closed won).
  A rep can override the category on an open deal. The weighted amount is value × probability.
- **Deal health (0–100):** shown next to the rep's probability, with reasons. It looks at:
  - time in stage compared with the organization's norm;
  - close-date slips, and a close date that has passed;
  - days since the last activity;
  - whether a contact is linked and the proposal has been sent;
  - overdue tasks.

  Risk flags (stalled, slipped, no activity for 14 days, no contact) notify the owner and their manager.
- **Stage history:** every stage change is kept, which powers the velocity and funnel reports.
- **Big-deal alerts:** deals above a value threshold notify managers when they're created or change stage.

### Companies and contacts
- Directory views with search, filters and drawers. Contacts carry industry, country, state,
  nature of business and a **category**; categories feed campaign audiences.
- **Website and LinkedIn links** are checked: only `http`, `https` and `mailto` are accepted.
- **Deleting a company that still has children** (contacts, deals, leads) is refused with counts,
  unless a Manager chooses **cascade**, which also moves the children to Trash.

### Duplicates (Manager and above)
- **Detection:** records are matched on normalized email, phone and web domain, plus fuzzy names
  (Jaro-Winkler, phonetic and initials).
- **Duplicate rules:** for each record type, you choose *Allow*, *Warn* or *Block*, separately for
  creating and for editing. With *Warn*, people see the possible matches and can open one or
  save anyway; saving anyway is audited.
- **Merge:** for leads, contacts and companies. The primary record's values win, its empty
  fields are filled from the others, the oldest creation date is kept, and every related item
  (activities, tasks, notes, files, pages, messages, deals and so on) moves to the primary.
  **Merges can't be undone.**

## 1.4 Execution

| Module | What it does |
|---|---|
| **Tasks** | Board (To do / In progress / Done) or table. Can be linked to a lead, deal, contact, company or project. Completion time is recorded automatically |
| **Projects** | Cards with status, priority, owner, members (owner / manager / member / viewer) and milestones with progress |
| **Todo lists** | Personal or shared checklists with sub-items and pinning |
| **Calendar** | Month and agenda views. Managers see the team's calendar; others see their own |
| **Meetings** | Meetings linked to CRM records, with outcomes; the organizer is notified |
| **Reminders** | Personal reminders that send a notification when they're due |

## 1.5 Knowledge

| Module | What it does |
|---|---|
| **Documents** | Pages built with the block editor; older plain-text documents can be converted to blocks |
| **Notes** | Quick notes, which can be pinned and linked to a record. AI automations can write their output here |
| **Databases** | Build your own Notion-style tables with 12 field types: text, number, date, select, multi-select, checkbox, URL, email, phone, file, **relation** (link to records in another database) and **formula**. Formulas are computed safely on the server: arithmetic, text joining, comparisons, `IF / AND / OR / NOT / ROUND / ABS / LEN / CONCAT`. A board view appears for select fields |
| **Whiteboards** | A freeform canvas with sticky notes, text, shapes, images and **connectors** (lines or arrows between two items that follow them as they move) |

## 1.6 Engagement

### Activity timeline
An append-only log of calls, emails, meetings, notes, WhatsApp messages, LinkedIn and stage changes.
Nothing can be edited or deleted. To fix a mistake you add a **correction**: the corrected version
is shown, and the original stays visible.

### Campaigns
- **Channels:**
  - **WhatsApp:** sends approved templates through Meta's Cloud API.
  - **Email:** sends through your connected SMTP mailboxes. Every email carries a one-click
    unsubscribe link, and opted-out addresses are skipped.
  - **SMS:** sends through Azure Communication Services.
- **Flow:** draft → submitted → approved / rejected → scheduled → sending → completed, with
  pause, resume and cancel. Only a Super Admin or VP Sales can approve or reject, and a
  rejection needs a reason.
- **Audience:** you can preview the audience first (how many, and the first 50 recipients), then add it.
- **Delivery:** each recipient gets the message at most once, even after crashes or with several
  servers running. Progress refreshes live while sending.

### WhatsApp
- **Conversations:** a chat view with per-message ticks (sent → delivered → read → failed).
- **Senders:** you can connect more than one sender number and choose which one sends.
- **Consent:** an incoming **STOP** opts the number out and **START** opts it back in, with history
  kept. Sending to an opted-out number is blocked, and campaigns skip it.
- **Webhooks:** inbound webhooks are verified with Meta's signature.

### Sequences
Multi-step cadences of email, WhatsApp and task steps, each with a delay before it. Enroll a lead or
contact from its drawer. The rules:
- **A reply stops the sequence.** Any inbound WhatsApp message, or an email reply picked up by
  the inbox watcher, ends it. Auto-replies are ignored.
- **Missing details skip a step, not the enrollment.** If the contact has no email, no phone, or
  has opted out, that step is skipped.
- **A hard failure stops that channel only.** After a bounce or undeliverable message, later steps
  on the same channel are skipped; other channels carry on.

### Email accounts (Settings, Super Admin / VP Sales)
Connect a mailbox over SMTP and IMAP. **Watch inbox for replies** polls the mailbox every
5 minutes and logs replies from known leads and contacts on their timeline. Watching the inbox also:
- stops sequences when someone replies;
- handles bounces;
- treats an "unsubscribe" reply as an opt-out.

### AI automations (Manager and above)
Each automation is **Trigger → Action**:
- **Triggers:**
  - manual;
  - lead created;
  - stage changed;
  - campaign approved;
  - **scheduled** (a cron expression and time zone);
  - **webhook** (an HMAC-signed inbound URL).
- **Actions:**
  - generate content, prepare a strategy, analyse data;
  - send an email, read an email;
  - call a custom API.

Calls to a custom API are protected against server-side request forgery (SSRF): only public
`https` addresses are allowed, redirects are blocked, and there are timeouts and a size limit.
Generated output is saved as a **note on the record** that triggered it. Every run is logged with
its status, any error and how long it took.

## 1.7 Insights

| Module | What it shows |
|---|---|
| **Reports** | Pipeline by stage, the lead funnel and sources, the **deal funnel** (a won deal counts as having passed every stage), **win/loss** by owner and by reason, **deal velocity** (average days per stage, days to win), team performance |
| **Forecast** | Weighted pipeline by category for a month or quarter, a three-period trend, quota attainment, and a per-owner or per-team table with inline quota editing (Manager and above) |
| **Audit log** | Every change, sign-in, export and permission denial. Super Admin and VP see the whole organization; Managers see their own team. Filterable, paginated and exportable to CSV |

## 1.8 Administration

### Admin console (Super Admin)
- **Overview:**
  - active and locked users, failed sign-ins and storage;
  - a 7-day activity chart and users by role;
  - a **Needs attention** list (locked accounts, failing webhooks, many permission denials,
    AI not configured).
- **Users:** search and filter, then act on each account:
  - change the role, deactivate or reactivate;
  - **unlock** after failed sign-ins;
  - **reset the password**, which forces a new one at next sign-in;
  - **sign them out on every device**.

  You can't change your own role or deactivate yourself, and the last Super Admin can't be demoted.
- **Security:** a feed of sign-ins, failed sign-ins, lockouts, password changes, forced sign-outs,
  permission denials, exports and merges.
- **Configuration:** one place that links to every setting in the app.
- **System:** version, profile, database schema version, pending migrations, uptime, memory,
  and which optional services are switched on. Secrets are never shown.

### Integrations (Super Admin)
API keys for the **Master API**, and signed outbound **webhooks** with a delivery log, test sends and
secret rotation. Integrator details are in [docs/MASTER_API.md](docs/MASTER_API.md).

### Settings
- **Profile:** your name, initials and colour, changing your password, and signing out on all devices.
- **AI assistant:** Anthropic or OpenAI, a model, and an API key. The key is stored encrypted and
  shown only as a hint.
- **Email accounts** and **Organization**.

### Trash
Soft-deleted leads, deals, companies, contacts and tasks, with one-click restore.

### Notifications
An in-app bell, plus optional **web push** through Azure Notification Hubs.

---

# Part 2 — Developer documentation

## 2.1 Technology stack

| Layer | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.3 (Web, Data JPA, Security, Validation, Mail, Actuator), Hibernate 6 |
| Database | PostgreSQL 16 in production; H2 (PostgreSQL mode) for `local` and `test`. **Flyway** owns the schema (`ddl-auto: validate`) |
| Auth | Stateless JWT (HS256, `jjwt`), BCrypt(12) passwords, hashed API keys |
| Scheduling | Spring `@Scheduled` plus **ShedLock** (JDBC, database clock) so each job runs on one replica |
| Rate limiting | **Bucket4j** in memory, or Redis-backed when `REDIS_ENABLED=true` |
| Files | Local disk by default; **Azure Blob Storage** when configured |
| Messaging | Meta WhatsApp Cloud API, SMTP/IMAP (JavaMail), Azure Communication Services SMS, Azure Notification Hubs web push |
| AI | Anthropic Messages API / OpenAI, keys configured per organization |
| Observability | Request ids (`X-Request-Id`), structured error bodies, Actuator health probes, optional **Application Insights** |
| API docs | springdoc OpenAPI 3 at `/api/docs` (spec at `/api/docs/openapi.json`) |
| Frontend | React 18, TypeScript, Vite 5, Tailwind 3, TanStack React Query 5, Zustand, React Router 6, Recharts, lucide icons, Inter Variable (self-hosted) |
| Testing | JUnit 5, MockMvc, Spring Boot Test, Testcontainers PostgreSQL; Vitest and Testing Library; ESLint (typescript-eslint, react-hooks) |
| Delivery | Multi-stage Dockerfiles (non-root), unprivileged nginx serving the SPA, Docker Compose, Azure Pipelines |

## 2.2 Architecture

```
Browser (React SPA)  ──/api──►  nginx (web image)  ──►  Spring Boot API  ──►  PostgreSQL
        ▲                                                │  │  │
        │ web push (Notification Hubs)                   │  │  ├─► Azure Blob (files)
        │                                                │  ├────► Meta WhatsApp / SMTP-IMAP / ACS SMS / AI providers
Other platforms ──X-API-Key──► /api/master/v1 ───────────┘  └────► Outbound webhooks (HMAC-signed)
```

**Request pipeline, in order:**
1. `RequestIdFilter` assigns or echoes `X-Request-Id`.
2. Spring Security chain:
   - `RateLimitFilter` applies a per-IP ceiling.
   - `ApiKeyAuthFilter` authenticates API keys, accepted only on `/api/master/**`.
   - `JwtAuthFilter` verifies the user token (token version, organization, active account, forced password change).
   - Authorization rules run.
   - `IdempotencyFilter` handles POST/PATCH requests that carry `Idempotency-Key`.
3. The controller.
4. `PermissionService` makes the access decision.
5. Repositories.
6. `AuditService`, which also emits webhooks.

**Rules every new piece of code follows:**
- **Tenant isolation:** every row has `organization_id`. Look records up with
  `findByIdAndOrganizationId`, never with a bare `findById`. Check ids that arrive in a request
  body with `TenantGuard`.
- **External calls run after commit:** WhatsApp, email, SMS, AI, webhooks and push are never
  called inside a database transaction. They are dispatched from
  `TransactionSynchronization.afterCommit` on a bounded executor (`IntegrationAsyncConfig`), with
  connect and read timeouts.
- **At-most-once background work:** jobs claim rows atomically, with `FOR UPDATE SKIP LOCKED` on
  PostgreSQL and a conditional `UPDATE … WHERE status = ...` on H2. Each item is processed in its
  own transaction. Every job carries `@SchedulerLock`.
- **Soft delete:** core records use `deletedAt` / `deletedBy` / `deleteReason` plus
  `@SQLRestriction`. Trash and restore use native queries.

## 2.3 Codebase map

### Backend: `src/main/java/com/betteranalytics/crm`

| Package | Responsibility |
|---|---|
| `config` | Security chain, scheduling and ShedLock, async executors, Redis toggle, first-admin bootstrap, secret re-encryption on key rotation, Java Flyway migrations (`config/migration`) |
| `security` | JWT, API keys, rate limiting (`ratelimit/`), idempotency, password policy, AES-GCM secret encryption (`SecretCrypto`, `EncryptedStringConverter`), SSRF guard (`UrlSafety`), link validation (`UrlFields`), JSON error writer |
| `permission` | `PermissionService` (tenant → feature → record scope → field mask), `FeaturePermissionTable` (the role matrix), `ResourceType`, `Action`, `Scope`, optional Redis scope cache |
| `controller` | REST controllers, one per resource (43) |
| `core` | CRM core helpers: partial updates (`Patch`), paging/sort params (`ListParams`), JPA Specifications (`Specs`), scoped queries for the core objects (`ScopeSpecs`), field masks (`CrmMasks`), lead workflow (claim/convert), company cascade delete, follow-up job, pipeline settings |
| `pipeline` | Stage history, forecast, analytics, deal health and the nightly health job |
| `dedupe` | Match keys, matching rules, duplicate guard on create/update, review, merge, Java backfill migration |
| `routing` | Round-robin lead routing |
| `sequences` | Sequence engine, scheduler, channel senders, inbound reply classifier |
| `service` | Integrations and cross-cutting services: AI, automations, WhatsApp, campaign dispatcher, email (SMTP/IMAP, inbox poller, consent), SMS (ACS), push (Notification Hubs), files, notifications, audit, webhooks, tenant guard |
| `master` | The Master API gateway (`MasterApiController`) and the in-process dispatcher |
| `admin` | Admin console API |
| `entity`, `repository`, `dto` | JPA entities (59), Spring Data repositories, request DTOs |
| `exception` | `GlobalExceptionHandler`: maps every error to `{error, code, requestId}` |

### Frontend: `frontend/src`

| Path | Responsibility |
|---|---|
| `App.tsx`, `main.tsx` | Routes (lazy-loaded pages) and providers; service-worker registration in production |
| `components/layout` | App shell, floating sidebar, top bar, mobile bottom nav, command palette (⌘K), notifications panel, protected route |
| `components/ui` | Design-system pieces: DataTable (server pagination and sort), Drawer, Modal, Tabs (segmented control), Kanban, Field, Badge, StatTile, EmptyState, QueryError (error + Retry), `useConfirm`, `useReason`, `SafeLink` / `safeHref`, AttachmentList |
| `features/<module>` | One folder per module (leads, opportunities, forecast, duplicates, sequences, campaigns, whatsapp, automations, databases, whiteboard, recordpages, admin, integrations…) with page, drawers, forms, local types and tests |
| `lib` | `api.ts` (axios client, `ApiError`, `downloadFile`), `auth.ts` (session store, logout hooks, cross-tab sync), `queryClient.ts`, `format.ts`, `theme.ts`, `toast.ts`, `types.ts` |
| `index.css` | Design tokens and component classes (glass surfaces, controls, tables, Kanban, navigation) |
| `public/` | Logo and icons, `site.webmanifest`, service worker (`sw.js`: static cache plus push) |

### Database migrations: `src/main/resources/db/migration`

Versions are grouped by area so parallel work never collides. Never edit an applied migration;
always add a new one.

| Range | Area |
|---|---|
| V1–V16 | Baseline schema, roles, soft delete, workspaces, campaigns, AI, attachments, security hardening (token version, API keys, webhooks), integrations hardening, ShedLock |
| V20–V21 | CRM core: optimistic locking, follow-ups, pipeline settings, list indexes |
| V30 | Campaign send records and WhatsApp consent |
| V40–V42 | Workspace tools: single default workspace, whiteboard connectors, indexes |
| V50 (Java) | Encrypts existing plaintext secrets |
| V60–V62 | Stage history, forecast categories and health, quotas |
| V65–V68 | Duplicate match keys (V66 is a Java backfill), duplicate rules, lead routing |
| V70–V71 | Sequences, idempotency keys |
| V80–V81 | Email campaigns, email consent, inbox polling |

Migrations must run on **both** H2 (PostgreSQL mode) and PostgreSQL 16:
- use plain `TIMESTAMP`;
- no partial indexes; for "exactly one" rules, use a nullable marker column plus a unique index;
- no PostgreSQL-only functions.

## 2.4 Security model

| Concern | Implementation |
|---|---|
| Sessions | JWT with issuer, `orgId` and `ver` claims. Bumping `token_version` revokes every session: password change or reset, deactivation, role change, "sign out everywhere", admin force-logout |
| Sign-in | Rate limits per IP and per email (Bucket4j or Redis); 5 failures lock the account for 15 minutes; a dummy BCrypt check runs for unknown emails so response timing doesn't reveal which emails exist; failed sign-ins are audited |
| Passwords | At least 10 characters, a letter and a digit, at most 72 bytes; not a common password (~1,000-entry list) and not containing your name or email; changing it requires the current password |
| First admin | The seeded admin must change password; `BOOTSTRAP_ADMIN_PASSWORD` sets the initial one only while the seed hash is untouched |
| Secrets at rest | AES-256-GCM (`enc:v1:` prefix) for AI keys, WhatsApp tokens, SMTP/IMAP passwords and webhook secrets. All are write-only in JSON. `ENCRYPTION_KEY_PREVIOUS` supports rotation, with automatic re-encryption |
| Startup safety | Outside `local`/`test`, the app refuses to start without `JWT_SECRET` and `ENCRYPTION_KEY` of at least 32 bytes, or if they're equal or a known default |
| API keys | `bcrm_` + 40 random characters, stored only as SHA-256; scope `read` or `read_write`; act as a chosen user; only accepted on `/api/master/**`; cannot reach credential-management endpoints |
| Outbound calls | `UrlSafety` allows only public addresses, re-checks them after DNS resolution, follows no redirects, and applies timeouts and size caps |
| Inbound webhooks | WhatsApp: `X-Hub-Signature-256` HMAC. Automations: `X-Signature` HMAC with a ±5-minute timestamp. SMS events: a shared secret |
| XSS | Links accept only `http`/`https`/`mailto`, checked on both client and server; `dangerouslySetInnerHTML` is banned by ESLint; strict CSP in nginx; uploads are content-sniffed and served `nosniff` with a sandbox CSP |
| Errors | One JSON shape, `{error, code, requestId}`, with no stack traces; firewall rejections return JSON too |
| CORS | Off unless `CORS_ALLOWED_ORIGINS` is set |
| Auditing | Every create, update and delete, plus sign-ins, exports, merges, permission denials and admin actions |

## 2.5 Permissions: how to use them

```java
User actor = currentUser.get();
// tenant → feature (role matrix) → record scope (own/team/org) → throws AccessDeniedException (403)
permissionService.require(actor, Action.EDIT, ResourceType.LEAD, lead.getOrganizationId(), lead.getOwnerId());

// list endpoints: put the visible-owner scope into the query instead of filtering in Java
PermissionService.OwnerScope scope = permissionService.ownerScope(actor, Action.VIEW, ResourceType.LEAD);
Specification<Lead> spec = Specs.<Lead>org(actor.getOrganizationId())
        .and(Specs.ownedWithin(scope, "ownerId"))            // ALL, or owner-id set (+ unowned pool)
        .and(Specs.search(q, "name", "email", "businessName"));
// For the core objects, the injectable ScopeSpecs bean wraps this: scopeSpecs.leads(actor)
```

To add a rule:
- Add a row to `FeaturePermissionTable`. Combinations that aren't listed are denied (fail closed).
- Support is read-only centrally in `lookup()`, so a rule can never accidentally give Support write access.
- Field masks live in `PermissionService` and `CrmMasks`.

## 2.6 APIs

- **Application API:** `/api/**` (JWT). The full, always-current reference is the Swagger UI at
  **`/api/docs`**.
- **Master API:** `/api/master/v1`, a single entry point for other platforms.
  - `GET /api/master/v1`: a self-describing catalogue of every operation.
  - `ANY /api/master/v1/{path}`: proxied in-process to `/api/{path}`, with the same permissions,
    masking, audit and webhooks.
  - `POST /api/master/v1/batch`: up to 50 operations, optionally atomic, with `{{opId.body.field}}`
    references to earlier results.
  - `Idempotency-Key` for safe retries.
  - Webhooks: `X-BetterCRM-Signature: sha256=HMAC(secret, timestamp + "." + body)`; retries after
    10 s, 1 min and 5 min; auto-disabled after 20 consecutive failures.
- **Conventions:**
  - Lists accept `page` (0-based), `size` (≤ 200), whitelisted `sort`, `search` and filters, and return `{count, data, page, size}`.
  - `PUT` and `PATCH` change only the fields you send.
  - A stale `version` returns `409 STALE_UPDATE`.
  - Duplicate warnings return `409 DUPLICATE_WARNING` with candidates; bypass with `?allowDuplicate=true`.

## 2.7 Local development

```bash
# Backend: H2 file DB, dev-only secrets, nothing to install but JDK 21+
./mvnw spring-boot:run -Dspring-boot.run.profiles=local

# Frontend: proxies /api to :8080 (override with API_PROXY_TARGET)
cd frontend && npm ci && npm run dev
```

| Command | What it runs |
|---|---|
| `./mvnw verify` | Every backend test (MockMvc on H2) and `*IT` Testcontainers PostgreSQL tests (these skip without Docker) |
| `npm run lint` | ESLint with zero warnings allowed, then `tsc` |
| `npm test` | Vitest |
| `npm run build` | Production bundle |
| `docker compose up --build` | PostgreSQL, API and web (needs `.env`; see `.env.example`) |

**Testing conventions:**
- HTTP tests extend `support/IntegrationTest`, which boots the full app on H2 with every
  migration applied. Each test creates its own organization and users, with helpers
  `newOrg()`, `user(org, Role)`, `getAs/postAs/putAs/patchAs/deleteAs`.
- External services are tested through small seams: `PushService.Transport`, `SmsSender`,
  `InboxFetcher`, the mail-sender factory and channel senders. Tests never call the network.
- Every new endpoint gets tests for:
  - a user from another organization (404 or 403);
  - a role without access (403);
  - Support being read-only;
  - field masking, where it applies.

## 2.8 How to extend the system

**Add a CRM module (backend):**
1. Write a migration in the next free range: table with `organization_id`, timestamps,
   soft-delete columns if needed, and indexes for your filters and sorts.
2. Add the entity and repository, with `findByIdAndOrganizationId` and a Specification for the list.
3. Add a `ResourceType` and rules in `FeaturePermissionTable`.
4. Write the controller:
   - `permissionService.require(...)` with the record's real owner;
   - `Patch` for partial updates;
   - `ListParams` + `Specs` (`org`, `ownedWithin`, `search`) for lists;
   - `TenantGuard` for any referenced ids;
   - `auditService.log(...)` on every mutation.
5. To publish webhooks, add the event names to `WebhookService.EVENTS` and the resource mapping;
   `AuditService` then emits them automatically.
6. Write the tests listed in §2.7.

**Add a page (frontend):**
1. Create `features/<name>/<Name>Page.tsx`.
2. Use the existing pieces: `PageHeader`, `glass-card`, `DataTable` with server paging, `Drawer`,
   `Field`, `Tabs`.
3. Every query needs a `QueryError` with Retry. Hide write actions when `isReadOnly(role)`.
4. Add a lazy route in `App.tsx` and an item in `components/layout/nav.ts`, with `minRole` if needed.
5. Use `SafeLink` / `safeHref` for any user URL, and `downloadFile` for authenticated downloads.

**Add a messaging channel:**
1. Implement a sender behind an interface, the way `SmsSender` and `SequenceChannelSender` work.
2. Reuse the `CampaignDispatcher` claim, at-most-once and counter flow.
3. Add a consent store or reuse an existing one.
4. Expose the channel's configured state through `/api/campaigns/channels` and the Admin → System tab.

**Add an automation trigger or action:** extend `AiAutomationService.TRIGGER_TYPES` / `ACTION_TYPES`,
add a branch in `execute(...)`, and if it's user-configurable, add it to `automationConfig.ts`
in the editor. Outbound actions must go through `UrlSafety`.

**Add a background job:** `@Scheduled(...)` plus `@SchedulerLock(name = "area.job")`. Claim work in
small batches, one transaction per item, and make external calls after commit. In
`application-test.yml`, give the job a long initial delay so it stays idle during tests.

## 2.9 Deployment summary

The target is **Azure**:
- Container Apps for the API and web images;
- Azure Database for PostgreSQL Flexible Server (`azure` profile, TLS required);
- Blob Storage for files;
- optional Azure Cache for Redis, Application Insights, Communication Services (SMS) and Notification Hubs (push);
- Key Vault references for secrets.

The CI pipeline (`azure-pipelines.yml`) runs the backend tests on JDK 21, lints, tests and builds
the frontend, then builds both images. `VITE_API_URL` comes from a pipeline variable. Health
probes are at `/actuator/health/liveness` and `/readiness`. The full checklist is in
[DEPLOYMENT.md](DEPLOYMENT.md).

---

# Part 3 — Future expansion

Ordered roughly by value for effort. Items marked **(decision)** need a product or business decision first.

### Near term
| Item | Why | How it fits |
|---|---|---|
| **Per-workspace data scoping** (decision) | Separate pipelines or business units inside one organization | Workspaces already exist with exactly one enforced default. Next steps: a `workspace_id` column on CRM tables (backfilled to the default), an `X-Workspace-Id` header in `lib/api.ts`, a workspace filter in `Specs` / `ScopeSpecs`, and a switcher in the top bar with cross-tab sync. This touches every query, so it was deliberately deferred |
| **SSO with Microsoft Entra ID, plus SCIM provisioning** | Single sign-on for an Azure organization; automatic joiners and leavers | Add Spring Security OAuth2 login next to JWT, map Entra groups to roles, and add a SCIM 2.0 `/Users` / `/Groups` endpoint driving `UserController` logic |
| **GDPR / DPDP tooling** | Data-subject export and erasure on request | Export a person's records as JSON (lead, contact, activities, messages, consent history); erase by anonymising personal fields and keeping the audit trail |
| **Field-level audit view** | Readable "who changed what" per record | The audit log already stores before/after JSON, so this needs a diff panel in record drawers (`auditDiff.ts` exists) |
| **Delivery reports for SMS and email** | Real delivered/opened status for non-WhatsApp channels | Event Grid `SMSDeliveryReportReceived` into the existing SMS events endpoint; email opens via a tracking pixel (opt-in, consent-aware) |
| **Cluster-wide send throttles** | Accurate per-mailbox limits with several replicas | Move `SendThrottle` and the failure counters to Redis, the way `RedisRateLimitBackend` works |

### Medium term
| Item | Why |
|---|---|
| **AI deal-health model** | Train on the organization's own won and lost history (stage durations, activity patterns), learning from the rules-based score already stored with its factors (see the research doc: Gong / Revenue.io approach) |
| **Data enrichment** | Firmographics and contact enrichment by domain through a provider, behind an `EnrichmentProvider` interface; respects duplicate rules |
| **Calendar and email sync (Microsoft 365)** | Two-way Outlook calendar and mail sync through Microsoft Graph, replacing IMAP polling with change notifications |
| **Quotes, products and price books** | Line items on deals feeding forecast amounts; PDF quotes stored as attachments |
| **Approval rules engine** | Configurable approvals (discounts, big deals) reusing the campaign approval flow |
| **Custom fields on core objects** | Reuse the custom-database property types and formula engine on leads, deals and contacts |
| **Mobile app** | React Native or a PWA-first shell using the same API; Notification Hubs already covers mobile platforms |

### Long term
| Item | Why |
|---|---|
| **Multi-tenant SaaS** (decision) | Billing, metering, plan limits and self-serve onboarding. The data model is already multi-tenant end to end |
| **Event bus** | Move post-commit work to Azure Service Bus (outbox pattern) for durable retries across replicas |
| **Reporting warehouse** | Stream audit and webhook events to a warehouse (Fabric / Synapse) for cross-system BI |
| **Public developer portal** | Publish the Master API catalogue and OpenAPI with a sandbox organization |

### Known limitations today
- Without Redis, rate limits and send throttles apply per replica.
- Email replies are noticed by inbox polling, every 5 minutes by default, not by push.
- Inbound SMS STOP needs an Event Grid subscription to be created.
- Real SMTP, IMAP, Azure and Meta endpoints are tested only through fakes in CI.
- PostgreSQL-only paths (`SKIP LOCKED`, `PERCENTILE_CONT`) run in the Testcontainers suite, which needs Docker.
