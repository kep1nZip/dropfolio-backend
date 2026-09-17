# Dropfolio

Repo dipisah dua:
- `/backend` — Spring Boot 3.3.x (Java 21) modular monolith. Source of truth: PRD.md,
  SYSTEM_ARCHITECTURE.md, ERD.md, API_CONTRACT.md v1.3, TECHNICAL_SPEC.md v1.1,
  CLAUDE_CONTEXT.md.
- `/frontend` — belum dikerjakan, lihat `/frontend/README.md`.

## Status implementasi backend

**Milestone 1 — `common/` : ACCEPTED.**
**Milestone 2 — Domain Models, Repositories & Authentication : ACCEPTED** (43/43 test PASS,
`mvn clean test` BUILD SUCCESS, verified di mesin Anda, JDK 21).
**Milestone 3 — Item Catalog (`item/`) : implementasi selesai, menunggu `mvn clean test` +
review PM.**

Lihat `MILESTONE_2_COMPLETION_REPORT.md` dan `MILESTONE_3_COMPLETION_REPORT.md` untuk detail
lengkap tiap milestone (file dibuat/diubah, endpoint, test, security verification, known
limitations, discrepancy/interpretation flag).

### Ringkasan Milestone 1 (`common/`)
- Response envelope, exception hierarchy, `GlobalExceptionHandler`
- Security: `JwtAuthenticationFilter`, `SecurityConfig`, custom 401/403 handler
- Redis: `RedisConfig`, `CacheKeys`
- `@Auditable` + AOP aspect, rate limit filter, `application.yml`, 4 circuit breaker instance

### Ringkasan Milestone 2 (`user/` + `auth/`)
- Entity `User`/`Role`/`UserRole` (ERD §2.1–2.3) + repository + migration `V1`, `V2`
- `POST /auth/register`, `POST /auth/login`, `POST /auth/refresh`, `POST /auth/logout`
- Refresh token rotation + reuse detection (Redis, `auth:refresh:{userId}:{familyId}`)
- Rate limit khusus scope `login` (10/15mnt/IP) dan `register` (5/60mnt/IP)
- `TokenVersionProvider` real implementation (instant JWT revoke)

### Ringkasan Milestone 3 (`item/`)
- Entity `Item`/`ItemType` (ERD §2.5) + repository (dynamic search/type filter via
  `Specification`) + migration `V3__init_items.sql`
- `GET /items` (public, search+type+pagination+sort whitelist), `GET /items/{id}` (public),
  `POST /items` (ADMIN, `@Auditable(ADMIN_CREATE_ITEM)`), `PATCH /items/{id}` (ADMIN,
  `@Auditable(ADMIN_UPDATE_ITEM)`, partial update, tidak ada DELETE — pakai `isActive`)
- 2 bug laten Milestone 1 ditemukan+diperbaiki di `GlobalExceptionHandler`: enum invalid di
  body/query param sebelumnya jatuh ke 500, sekarang 422 (general fix, bukan item-specific)

Belum dikerjakan (menunggu urutan CLAUDE_CONTEXT.md §12): `pricing/`, `drop/`, `steam/`,
`inventory/`, `portfolio/`, `alert/`, `notification/`, `scheduler/`, `admin/`.
`/users/me/*` endpoints sengaja belum dikerjakan (di luar scope Milestone 2/3).

## Menjalankan

```bash
cd backend
docker compose up -d       # mssql + redis
./scripts/init-db.sh       # sekali saja — buat database kosong `dropfolio`
cp .env.example .env       # isi JWT_SECRET dst, lihat SETUP.md
mvn clean test             # jalankan seluruh test (milestone 1 + 2 + 3)
mvn spring-boot:run        # Flyway otomatis jalankan V1/V2/V3 saat startup pertama
```

Lihat `SETUP.md` untuk panduan lengkap dari kondisi VSCode kosong.
