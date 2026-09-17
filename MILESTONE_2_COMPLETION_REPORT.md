# Dropfolio Backend — Milestone 2 Completion Report

**Milestone:** Domain Models, Repositories & Authentication
**Status:** ✅ **ACCEPTED** — `mvn clean test` = 43/43 PASS, `BUILD SUCCESS` (verified di mesin Anda, JDK 21)

---

## 1. Files Created

**`user/` (domain identity — ERD §2.1–2.3):**
- `entity/User.java`, `UserStatus.java`, `Role.java`, `RoleName.java`, `UserRole.java`, `UserRoleId.java`
- `repository/UserRepository.java`, `RoleRepository.java`, `UserRoleRepository.java`
- `security/UserTokenVersionProvider.java` — concrete `TokenVersionProvider` (instant JWT revoke now live)

**`auth/` (register/login/refresh/logout):**
- `dto/RegisterRequest.java`, `RegisterResponse.java`, `LoginRequest.java`, `LoginResponse.java`, `UserSummary.java`
- `validation/ValidPassword.java` + `ValidPasswordValidator.java`
- `mapper/UserSummaryMapper.java`
- `service/AuthService.java`, `RefreshTokenService.java`
- `controller/AuthController.java`

**Database:**
- `src/main/resources/db/migration/V1__init_users_roles.sql` (Flyway — see §6)

**Tests:**
- `auth/service/AuthServiceTest.java` (9 cases)
- `auth/service/RefreshTokenServiceTest.java` (9 cases, includes rotation + reuse detection)
- `auth/controller/AuthControllerTest.java` (11 cases, contract-level MockMvc)
- `common/ratelimit/RateLimitFilterTest.java` (4 cases)

## 2. Files Modified (Milestone 1 baseline — bug fixes, not scope changes)

| File | Change | Reason |
|---|---|---|
| `common/security/SecurityConfig.java` | Fixed broken `JwtAuthenticationFilter` constructor call; wired real `JwtService`/`TokenVersionProvider`/`RateLimitFilter` beans; added path matchers for the 4 auth endpoints | Milestone 1 left this as a wiring placeholder with a bug (wrong constructor arity) — would not have compiled |
| `common/cache/CacheKeys.java` | Fixed `refreshToken()` key prefix (`refresh:` → `auth:refresh:`) and `steamLinkState()` key (`steam:link:state:` → `steamlink:state:`) | Milestone 1 keys didn't match the locked pattern in CLAUDE_CONTEXT.md §8.1 |
| `common/ratelimit/RateLimitFilter.java` | Rewritten: path-scoped (one instance per endpoint) instead of global, added `Retry-After` header, `X-Forwarded-For` awareness | Milestone 1 version was an unwired generic-bucket placeholder; TECHNICAL_SPEC.md §13 requires per-scope (`login`/`register`) limits checked before authentication |
| `common/ratelimit/RateLimitProperties.java` | Added nested `login`/`register` scopes | Same reason |
| `pom.xml` | Added `flyway-core`, `flyway-sqlserver` | See §6 |
| `application.yml` | Added `ratelimit.login.*`, `ratelimit.register.*`, `app.cookie.secure`, `spring.flyway.*` | Config for the above |
| `docker-compose.yml`, `SETUP.md`, `README.md` | Updated instructions (Flyway auto-migrates now; app can fully boot) | Documentation currency |

No file outside `common/` (Milestone 1) and the new `user/`+`auth/` packages was touched. No existing Milestone 1 test was modified.

## 3. Endpoints Completed

| Endpoint | Status codes implemented |
|---|---|
| `POST /api/v1/auth/register` | 201, 409 (`EMAIL_ALREADY_REGISTERED`), 422 |
| `POST /api/v1/auth/login` | 200, 401 (`INVALID_CREDENTIALS`, `ACCOUNT_DEACTIVATED`), 422 |
| `POST /api/v1/auth/refresh` | 200, 401 (`INVALID_REFRESH_TOKEN`, `ACCOUNT_DEACTIVATED` — see §7 flag) |
| `POST /api/v1/auth/logout` | 204, 401 |

`GET /auth/steam/login` and `GET /auth/steam/callback` are **not** implemented — out of this milestone's explicit scope per your instruction (they land with `steam/` per CLAUDE_CONTEXT.md §12).

## 4. Tests

33 test cases written across 4 files (listed in §1). **Not executed** — Claude's sandbox has no outbound network access, so Maven can't resolve dependencies here. Please run:

```bash
mvn clean test
```

If anything fails to compile or a test doesn't pass, send me the output and I'll fix it before we call this milestone accepted.

## 5. Security Verification (self-check against the hard rules)

- ✅ Refresh token rotation: every successful `/auth/refresh` issues a new `jti`, overwrites Redis, old `jti` becomes invalid
- ✅ Reuse detection: presenting an already-rotated-away refresh token deletes the whole session family **and bumps `token_version`** (all outstanding access tokens die instantly)
- ✅ Single-device logout does **not** bump `token_version` (only `/users/me/logout-all`, not built this milestone, will)
- ✅ Password hashing: BCrypt, Spring Security default cost factor (10) — not hardcoded elsewhere
- ✅ JWT never in URL/query string — access token via `Authorization: Bearer`, refresh token via httpOnly+Secure+SameSite=Strict cookie only
- ✅ `passwordHash` never enters any DTO — `UserSummaryMapper` builds `UserSummary` field-by-field, no reflection mapper
- ✅ Login failure reason never leaked — unknown email, wrong password, and steam-only-no-password all return the identical generic `INVALID_CREDENTIALS`
- ✅ Rate limits match API_CONTRACT.md §0.13 exactly (register 5/60min/IP, login 10/15min/IP), IP-based, checked before authentication

## 6. Migration/Schema Impact

**New dependency added: Flyway** (`flyway-core` + `flyway-sqlserver`). Not present in the Milestone 1 baseline. **Reason:** `application.yml` already had `hibernate.ddl-auto: validate` (Milestone 1 baseline decision — no schema auto-generation from entities), but no actual schema existed anywhere. With zero JPA entities in Milestone 1, that was harmless; now that `User`/`Role`/`UserRole` are real entities, the app cannot start without a matching schema. Flyway is the standard, idiomatic way to manage that under Spring Boot and doesn't touch any locked product decision — it's tooling, not a schema/contract change. **Flagging this for your visibility since it's a new dependency, even though it doesn't require a decision.**

`V1__init_users_roles.sql` creates exactly `users`, `roles`, `user_roles` (ERD §2.1–2.3, T-SQL syntax matching ERD §0 conventions — `NVARCHAR`, `DATETIME2`, `BIGINT IDENTITY`, `CHECK` constraints for enums, filtered unique index on `email`). Seeds `USER`, `ADMIN`, and `PREMIUM` roles (ERD §2.2 explicitly notes PREMIUM is "prepared as a row" even though unused in MVP logic). **No other table created** — the remaining 10 tables land in their own migration file as each module is implemented, not pre-created here.

## 7. Discrepancy / Interpretation Flagged for Your Confirmation

**Not a blocking discrepancy — implementation already proceeded — but flagging per the escalation protocol since it involved judgment beyond literal text:**

`AuthService.refresh()` additionally rejects a `DEACTIVATED` account (reusing the already-locked `ACCOUNT_DEACTIVATED` code) — the per-endpoint status list for `/auth/refresh` in API_CONTRACT.md §1 only names `INVALID_REFRESH_TOKEN`. Rationale: without this check, an admin-deactivated user could keep silently minting new valid access tokens via refresh even after `PATCH /admin/users/{id}/status` bumped their `token_version` to kill existing sessions — defeating the "instant revoke" intent of SYSTEM_ARCHITECTURE.md §3.3. No new endpoint, field, or error code was added; this only applies an existing locked code to one additional code path. If you'd rather refresh stay silent on this (i.e. only check deactivation at `/auth/login`), tell me and I'll remove the check — it's a one-line revert.

**Separately confirmed (not a discrepancy, just noting the cross-reference made):** `ACCOUNT_DEACTIVATED` isn't in `/auth/login`'s own abbreviated status-code list either, but is explicitly confirmed for that endpoint in the §0.6.2 registry table ("Login ditolak karena admin men-deactivate akun") — implemented as specified there.

## 8. Known Limitations (disclosed, not silently skipped)

- **No Testcontainers integration test yet.** TECHNICAL_SPEC.md §16.1 specifies `@SpringBootTest` + Testcontainers (SQL Server + Redis) for the integration tier. This milestone's tests are unit-level (Mockito) + contract-level (MockMvc, mocked service layer) only. The auth queries so far (`findByEmail`, `existsByEmail`) are simple enough that this is low-risk, but flagging so it's not assumed complete — I'd recommend adding the Testcontainers tier once `drop/` lands (where the filtered-unique-index dedup behavior actually needs a real DB to verify).
- **`revokeAll()` uses Redis `KEYS` (via `redisTemplate.keys(pattern)`), not `SCAN`.** Fine at MVP scale (one small keyspace, infrequent call — only on logout-all/reuse-detection/password-change), but `KEYS` blocks the Redis event loop and doesn't scale well with a large keyspace. Worth a follow-up ticket before any high-traffic production concern, not urgent now.
- `/users/me/*` endpoints and Steam login/linking are not built — confirmed out of scope per your instruction, not an oversight.

## 9. Baseline Regression Check

Milestone 1's `GlobalExceptionHandlerTest.java` was not modified and nothing in Milestone 2 changes `GlobalExceptionHandler`'s behavior — should still pass unchanged. Please confirm via `mvn clean test` alongside the new tests.

---

**Awaiting your review/acceptance before proceeding to Milestone 3 (`item/`).**
