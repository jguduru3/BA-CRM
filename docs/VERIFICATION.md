# Verification log

What was actually run in this environment, what it caught, and what's still
unverified. Written so the next person doesn't have to re-derive any of this from
scratch or take "it should work" on faith.

## Environment

- Windows 10, PowerShell/Git Bash, JDK 17 installed (project targets 21 — see below),
  Maven 3.9.11, Node 20.19.4, npm 11.5.2. No Docker daemon, no reachable PostgreSQL.
- Maven **does** reach `repo.maven.apache.org` here — a prior README claimed otherwise
  based on a different sandbox; that claim did not hold in this environment.

## Backend

**Compile**: `mvn compile` fails with `release version 21 not supported` (only JDK 17
present). `mvn compile -Dmaven.compiler.release=17` — clean, zero errors, 157 files
(including every file added this session: `Attachment`, `WhiteboardBoard`,
`WhiteboardItem`, `FileStorageService`, `AttachmentController`, `WhiteboardController`,
plus edits to `Contact`, `ContactCategory`, `Lead`, `Opportunity`, `LeadController`,
`OpportunityController`). The Dockerfile already targets `eclipse-temurin:21-jdk-alpine`,
so this is a local-host limitation, not a project defect.

**Boot + migrate** (`-Dspring-boot.run.profiles=local`, H2 in PostgreSQL-compat mode):
first three attempts failed, each catching a **real, pre-existing, cross-engine SQL
bug** — not hypothetical, found by actually running Flyway against a real engine:

1. `V1__init.sql` — `leads.value` / `opportunities.value`: `VALUE` is a reserved word
   in H2 (not in Postgres). **Fixed**: renamed the physical column to `deal_value` in
   V1 and added `@Column(name = "deal_value")` to `Lead.value` / `Opportunity.value` —
   the Java field and JSON property name are untouched, so this is invisible to every
   API consumer (frontend included).
2. `V11__...sql` — two partial indexes (`CREATE INDEX ... WHERE ...`), a Postgres-only
   construct H2 doesn't parse. **Fixed**: dropped the `WHERE` clause (correctness is
   unaffected; Postgres loses a little index-size optimization, which doesn't matter
   at this scale).
3. `V12__attachments_whiteboards_contact_geo.sql` (this session's own migration) —
   used `TIMESTAMPTZ`, which H2 doesn't recognize, while every other migration in the
   project uses plain `TIMESTAMP`. **Fixed**: matched the existing convention.

After all three fixes: all 12 migrations apply, Hibernate validates the full schema
against all 47 entities, and the app reaches `Started CrmApplication`.

**Live endpoint smoke test** (real HTTP calls against the booted app, JWT from a real
login): `/api/health`, `/api/auth/login` (seeded admin), `POST/GET /api/whiteboards`,
`POST /api/whiteboards/{id}/items`, `POST /api/leads`, `POST /api/contacts` (with the
new industry/country/state/natureOfBusiness fields), `GET /api/contact-categories`
(auto-seed), `GET /api/ai-automations`, `GET /api/attachments`, `GET /api/departments`,
`GET /api/teams`, `GET /api/reports/dashboard` — all returned the expected JSON, no
500s.

**A second class of bug this caught**: Lombok generates `isX()` getters for fields
already named `isX` (e.g. `isActive`, `isDone`, `isShared`, `isSystem`), and Jackson's
default bean-naming strips the `is` prefix from the JSON key — so the wire property is
`"active"`, not `"isActive"`. This was verified empirically (a throwaway Jackson+Lombok
probe, not assumed from memory) after noticing the pattern, and matched against a live
`GET /api/contact-categories` response (`"active":true,"system":true`, confirmed). Every
frontend field reading one of these flags was corrected: `TodoItem.done`/`pinned`,
`TodoList.shared`, `ContactCategory.system`/`active`, `AiAutomation.active`. One related
surprise, `zIndex` → `"zindex"` (Jackson's acronym-folding for a getter with two leading
capitals), was pinned back to proper camelCase with `@JsonProperty("zIndex")` on the one
field in the codebase that had this exact pattern (grepped for others — none found) and
re-verified live (`"zIndex":0` in the response).

**Not verified**: a real PostgreSQL run (no instance reachable here) and the actual
Docker build (no daemon here). The SQL is standard Postgres syntax with nothing H2
required the H2-specific workarounds above for; the Dockerfiles were written and
reviewed but their first real build should happen somewhere with Docker before trusting
them for production.

## Frontend

`npx tsc --noEmit` — clean after fixing: a missing `vite-env.d.ts` (so `import.meta.env`
has a type), a `Map` naming collision (importing lucide-react's `Map` icon shadowed the
built-in `Map` class), and a generic-type mismatch in the custom-database board view.

`npx vite build` — failed first (the `@/` path alias used throughout wasn't registered
in `vite.config.ts`'s `resolve.alias`, only in `tsconfig.json`, which only affects
type-checking, not bundling). Fixed; clean build after, all ~30 route chunks produced.

## Net effect

Every bug listed above was a real defect that would have surfaced the first time
someone actually ran this stack, not a style nit — three cross-engine SQL failures that
would have broken local H2 dev entirely, a bundler config gap that would have broken
every production build, and a JSON-naming mismatch class that would have silently
produced `undefined` for checkbox/toggle state across four modules. All are fixed and
re-verified, not just patched and assumed fixed.
