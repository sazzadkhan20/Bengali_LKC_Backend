# Bengali LKC Backend

Spring Boot + PostgreSQL + Hibernate (JPA) backend for the **Bengali Local Knowledge Core** framework (LKC-BEN-SRS-001).

## Architecture

**Modular Layered Monolith** (presentation → application → domain → infrastructure), with bounded packages:

| Package | Responsibility |
| --- | --- |
| `web` | REST controllers, DTOs, exception advice, OpenAPI |
| `service` | Auth, submissions, arbitration, community, lexicon, export |
| `domain` | JPA entities + enums (UKC core + workflow) |
| `repository` | Spring Data JPA |
| `security` | JWT (cookie + Bearer), RBAC permission matrix, rate limit |
| `bootstrap` | Demo users + optional UKC XML import |
| `config` | CORS, OpenAPI, properties |

Schema is owned by **Flyway**; Hibernate runs with `ddl-auto: validate`.

### Security model

- Credential-based auth (no self-registration) — Platform Architect issues accounts
- Passwords: **BCrypt (strength 12)**
- Session: **JWT HS256** in httpOnly cookie `lkc_session` and/or `Authorization: Bearer`
- Seven-tier RBAC matching SRS §3.3 (`PermissionMatrix`)
- Anti-bot: honeypot, min submission time, CAPTCHA flag, daily rate limits
- IP rate limiting from `system_config.ip_requests_per_minute`
- Audit trail for auth, credentials, config, export, moderation

### UKC core tables (from ER / table-structure doc)

`lexicon` → `lexical_entry` / `synset` → `sense` → `sense_relation` / `synset_relation` (+ relation type lookup tables)

Workflow tables: users, credentials, submissions, arbitration, community, audit, export, system_config, wordnet_ili.

---

## Quick start (local)

Requirements: **Java 21**, **Docker Desktop** (must be running).

Postgres runs in a **dedicated Docker container** (`bengali-lkc-db` on host port **5433**).
On startup Flyway **auto-creates all tables** inside that database.

### IntelliJ IDEA (recommended)

1. Start **Docker Desktop** and wait until it shows Running.
2. Open this project as a Gradle project.
3. Run configuration **`BengaliLkcApplication`** (profile `local`) — or right‑click `BengaliLkcApplication` → Run.
4. With profile `local`, Spring Boot **auto-starts** `docker compose` service `db` if it is not already up, waits until Postgres is ready, then Flyway migrates.

Optional manual DB start before Run:

```bash
docker compose up -d db
# or
bash scripts/ensure-db.sh
```

### CLI

```bash
cp .env.example .env
bash scripts/ensure-db.sh   # starts dedicated Postgres + waits healthy
./gradlew bootRun           # profile=local; Flyway migrates automatically
```

Or all-in-one: `bash scripts/start-local.sh`

| Service | URL / connection |
| --- | --- |
| API | http://localhost:8080 |
| Swagger UI | http://localhost:8080/swagger-ui.html |
| Health | http://localhost:8080/actuator/health |
| Postgres | `localhost:5433` · db `bengali_lkc` · user `lkc_admin` |

> **If you see `Connection to localhost:5433 refused`:** Docker Desktop is not running, or the `db` container is not up. Start Docker, then `docker compose up -d db`, wait for healthy (`docker compose ps`), and run the app again.

### Demo credentials

| Username | Password | Role |
| --- | --- | --- |
| `architect` | `architect2026` | Platform Architect |
| `moderator` | `moderator2026` | System Moderator |
| `contributor` | `contributor2026` | Lexical Contributor |
| `editor` | `editor2026` | Lexical Editor |
| `chief` | `chief2026` | Chief Lexical Editor |
| `curator` | `curator2026` | Arbitration Curator |
| `mita.c` | `welcome-mita-2026` | Contributor (one-time password) |

---

## Full stack with Docker

```bash
# API + Postgres together (production-like)
docker compose --profile full up -d --build
```

Local IntelliJ runs should **not** start the `api` container — only `db`.
The `api` service is under Compose profile `full`.

This starts:

1. **PostgreSQL 16** (persistent volume `bengali-lkc-postgres-data`)
2. **API** image (multi-stage Temurin 21 build)

Optional MailHog: `docker compose --profile dev up -d`

### Import full lexicon XML

Set in `.env` / compose:

```env
IMPORT_LEXICON_XML=true
LEXICON_XML_PATH=/app/data/Bangla_Mono_Lingual_Lexicons.xml
```

The compose file mounts `Documents/Bangla Mono Lingual Lexicons.xml` into the API container.

---

## API surface (aligned with frontend `/api/*`)

| Area | Endpoints |
| --- | --- |
| Auth | `POST /api/auth/login\|logout\|password-otp\|change-password\|forgot-password` |
| Users / credentials / config | `/api/users`, `/api/credentials`, `/api/config` |
| Submissions | `POST /api/submissions`, `POST /api/submissions/{id}/action` |
| Arbitration | `POST /api/arbitration/{id}` |
| Community | `POST /api/community`, `POST /api/community/{id}` |
| Lookup | `GET /api/lookup/words\|synsets\|ili` |
| Export / audit | `GET /api/export`, `GET /api/audit/download` |
| Public | `GET /api/public/search\|browse\|stats\|words/{id}\|synsets/{id}` |
| Dashboard | `GET /api/dashboard/stats\|queues\|leaderboard` |
| Session | `GET /api/me` |

---

## Frontend integration

Point the Next.js app at this API (cookie is `lkc_session`, CORS defaults allow `http://localhost:3000`).

Suggested env on frontend:

```env
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

Use `credentials: 'include'` on `fetch` so the httpOnly session cookie is sent.

---

## Production notes

Step-by-step server setup, automatic deploys from `prod-*` release tags, backups and rollback:
see [DEPLOYMENT.md](DEPLOYMENT.md). The production stack lives in [`deploy/`](deploy/).

- Profile: `SPRING_PROFILES_ACTIVE=prod`
- Set a strong, private `JWT_SECRET` (≥ 32 chars). The `prod` profile refuses to start with a missing, short or development secret.
- Sessions end `JWT_REFRESH_TTL_DAYS` (default 7) days after sign-in. Token refreshes keep that deadline, so users must sign in again once it passes; changing the password starts a new session.
- Set `CORS_ORIGINS` to your real frontend origin(s)
- Set `SEED_DEMO_USERS=false` and `DEMO_EXPOSE_SECRETS=false`
- Terminate TLS at nginx/Apache (SRS §12.3); keep cookies `Secure` + `SameSite=None` when cross-site
- Database migrations apply automatically via Flyway on startup
- Healthcheck: `/actuator/health`. Only `health,info` are exposed by default; override with `ACTUATOR_ENDPOINTS`.
- Swagger UI and `/v3/api-docs` are off in `prod`; set `API_DOCS_ENABLED=true` to turn them on.

---

## Tech stack

- Java 21 · Spring Boot 3.4 · Gradle 8.13
- Spring Security · JJWT · Spring Data JPA / Hibernate
- PostgreSQL 16 · Flyway
- springdoc-openapi (Swagger UI)
- Docker multi-stage + Compose
