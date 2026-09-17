# Dropfolio — Milestone 5 Completion Report
## `drop/` Module — Manual Holdings CRUD

**Project:** Dropfolio Backend
**Milestone:** 5 — Drops (Manual Holdings CRUD)
**Status:** ✅ **IMPLEMENTED — AWAITING PM ACCEPTANCE**
**Date:** 6 September 2026

---

# 1. Documentation Updates (Done Before Coding, Per PM Instruction)

`API_CONTRACT.md` → **v2.1** and `CLAUDE_CONTEXT.md` → **v2.1**, both attached alongside this
report. Changes, all scoped strictly to the 5 PM decisions (no endpoint/method/response
shape/ownership rule touched):

| Doc | Change |
|---|---|
| `API_CONTRACT.md` §5 | `source (MANUAL\|MANUAL)` → `source (MANUAL)` |
| `API_CONTRACT.md` §5 | `minValue`/`maxValue` documented as filtering `acquisitionValueUsd`, explicitly not `currentValueUsd` |
| `API_CONTRACT.md` §5 | Removed `409` from `PATCH`/`DELETE /drops/{id}` status-code lists; cleaned the garbled leftover sentences in the same paragraphs |
| `API_CONTRACT.md` §0.6.2 | Fixed a stale example reference to the now-removed `DROP_NOT_EDITABLE` code |
| `CLAUDE_CONTEXT.md` §5 | Added an inline note locking the same 5 decisions next to the Drops endpoint checklist |
| `CLAUDE_CONTEXT.md` §7.3/§7.4 | Removed `DROP_NOT_EDITABLE`/`DROP_NOT_DELETABLE` from the domain code registry and exception hierarchy (also removed the stale `STEAM_*` codes sitting in the same table/line, since they reference endpoints that no longer exist anywhere in the v2.1 contract) |
| `CLAUDE_CONTEXT.md` §12/§13 | Fixed leftover "sebelum steam sync"/"duplicate drop dari re-sync" references — no such thing exists in this product |

Both files carry a changelog note at the top (`v2.1 changelog`) explaining exactly what changed
and why, same convention as the rest of the document chain.

---

# 2. Endpoints Implemented

All 5, per PM's final scope (including `GET /drops/{id}`, confirmed IN scope):

| Endpoint | Auth | Ownership |
|---|---|---|
| `POST /api/v1/drops` | Required | self |
| `GET /api/v1/drops` | Required | self |
| `GET /api/v1/drops/{id}` | Required | self |
| `PATCH /api/v1/drops/{id}` | Required | self |
| `DELETE /api/v1/drops/{id}` | Required | self |

No `SecurityConfig` change was needed — none of these paths had a matcher yet, so they already
fall under the existing default `anyRequest().authenticated()`.

---

# 3. Files Added

```text
drop/entity/Drop.java
drop/entity/DropSource.java

drop/repository/DropRepository.java
drop/repository/DropSpecifications.java

drop/dto/DropResponse.java
drop/dto/DropItemRef.java
drop/dto/CreateDropRequest.java
drop/dto/UpdateDropRequest.java

drop/mapper/DropMapper.java

drop/service/DropService.java

drop/controller/DropController.java

src/main/resources/db/migration/V5__init_drops.sql

drop/service/DropServiceTest.java
drop/controller/DropControllerTest.java
```

## Files Modified

```text
API_CONTRACT.md      (v2.0 -> v2.1, §1 above)
CLAUDE_CONTEXT.md     (v2.0 -> v2.1, §1 above)
```

**No other files touched** — `item/` and `pricing/` (Milestone 3/4) are consumed read-only,
`SecurityConfig.java` was not modified, no other module was implemented.

---

# 4. How Each PM Decision Was Implemented

1. **`GET /drops/{id}`** — implemented as `DropController.getById` / `DropService.getById`,
   using the same `findByIdAndUserIdAndDeletedAtIsNull` ownership pattern as PATCH/DELETE.
2. **`minValue`/`maxValue` filter `acquisitionValueUsd`** — `DropSpecifications.minAcquisitionValue`/
   `maxAcquisitionValue` predicate directly on `drops.acquisition_value_usd`. No join/subquery
   to `item_prices` was added for this filter, per PM's explicit instruction.
3. **`source (MANUAL)`, reject anything else with 422** — `DropSource` enum has exactly one
   value (`MANUAL`). An invalid query value (e.g. `?source=STEAM_SYNC`) fails Spring's enum
   binding and is caught by the existing `MethodArgumentTypeMismatchException` handler in
   `GlobalExceptionHandler` (unchanged, reused as-is) → `422 VALIDATION_ERROR`.
4. **`UpdateDropRequest.acquisitionValueUsd` — `null` = leave unchanged, no clear mechanism** —
   `DropService.update` only calls `drop.setAcquisitionValueUsd(...)` when
   `request.acquisitionValueUsd() != null`; there is no sentinel value or separate "clear" flag
   anywhere in the DTO or service.
5. **No `409` for `PATCH`/`DELETE /drops/{id}`** — neither method throws or handles any `409`
   condition; `DropNotEditableException`/`DropNotDeletableException` (pre-existing scaffolding
   from before the Steam-removal architecture change) are not referenced anywhere in `drop/`. No
   optimistic locking/version column/concurrency mechanism was added, per PM's explicit
   instruction not to build one.

---

# 5. Query Parameters (`GET /drops`)

`search` (item name, case-insensitive `LIKE`, via a correlated subquery against `items` — no
JPA association added to `Drop` for this, see `DropSpecifications` javadoc), `type`
(`CASE|SKIN|GRAFFITI`, same subquery approach), `source` (`MANUAL` only), `dateFrom`/`dateTo`
(range on `acquisitionDate`), `minValue`/`maxValue` (range on `acquisitionValueUsd`, §4.2),
`page`/`size` (same `page>=1`, `size 1..100` rules as Item Catalog), `sort` (whitelist:
`acquisitionDate`, `currentValueUsd`, `createdAt`).

**`sort=currentValueUsd` — implementation note (not a locked contract detail, purely how it's
built):** `currentValueUsd` isn't a stored column, it's a live snapshot from `pricing/`. Rather
than joining against a "latest price per item" subquery at the database level (which the PM
decision in §4.2 specifically avoided for the `minValue`/`maxValue` filter, for the same
underlying reason), sorting by this field is done by fetching the full filtered-but-unpaginated
result set, resolving each row's current price through the exact same `PricingService` used by
every other read path (identical Redis cache-aside/circuit-breaker behavior — nothing
pricing-specific was reimplemented), sorting in memory, then paginating the sorted list.
`totalElements`/`totalPages` in the response `meta` are computed from the full filtered set, so
pagination correctness is preserved. This is an MVP-scale trade-off (manual holdings are bounded
per user, not a public leaderboard over millions of rows) — flagging it here for visibility, not
because anything is broken.

Default sort when `sort` is entirely absent: `acquisitionDate,asc` — chosen purely for
consistency with `ItemService`'s established "default field ascending when unspecified"
convention (Milestone 3); not a locked contract default, since API_CONTRACT.md doesn't specify
one.

---

# 6. Ownership & ID-based Access

Every single-resource operation (`GET/{id}`, `PATCH/{id}`, `DELETE/{id}`) goes through
`dropRepository.findByIdAndUserIdAndDeletedAtIsNull(id, userId)` — never `findById()` plus a
manual check. A drop that doesn't exist, belongs to another user, or has already been
soft-deleted are all indistinguishable from the caller's point of view: all three return
`404 NOT_FOUND` via `OwnershipMismatchException` (reused, previously-unused scaffolding from
before Milestone 3 — its own javadoc already described exactly this use case). Verified in
`DropControllerTest` (`*_ownershipMismatch_returns404NotForbidden` — explicitly asserts `404`,
never `403`).

Current-user extraction required no new code: `JwtAuthenticationFilter` (Milestone 2 scaffolding)
already sets `Authentication.getPrincipal()` to the raw `Long userId`, so
`@AuthenticationPrincipal Long userId` in `DropController` works directly.

---

# 7. Validation

| Field | Rule | Mechanism |
|---|---|---|
| `itemId` (create only) | must exist **and** `isActive=true` | Service-level check → `404` (single code, not split — per API_CONTRACT.md §5) |
| `quantity` | `> 0` | `@Positive` |
| `acquisitionDate` | not in the future | `@PastOrPresent` |
| `acquisitionValueUsd` | optional; if present, `> 0` | `@Positive` (null-safe by Bean Validation semantics) |
| `sort` | whitelist only | Service-level check → `422` (reused `ValidationException`) |
| `type`/`source` query param | valid enum only | Reused `MethodArgumentTypeMismatchException` handler (Milestone 3) |

No new exception classes were created for Milestone 5 — every error case maps to an existing
class (`ResourceNotFoundException`, `OwnershipMismatchException`, `ValidationException`, Bean
Validation, `MethodArgumentTypeMismatchException`).

---

# 8. Database / Migration

`V5__init_drops.sql` follows ERD.md §2.7 (columns, FKs, indexes) exactly, with one deliberate
deviation, documented inline in both the migration and `Drop.java`'s javadoc:

**`created_at`/`updated_at`/`deleted_at` use `DATETIMEOFFSET(6)`, not the `DATETIME2` literally
written in ERD.md §2.7 for this table.** This is the same Instant/Hibernate 6 mapping fix already
applied twice in this codebase — `users`/`user_roles` needed an explicit `ALTER COLUMN` fix in
V2 after `DATETIME2` caused problems; `items`/`item_prices` (V3/V4) applied the fix from the
start instead of repeating the bug. ERD.md §2.7 wasn't updated to carry that fix forward for
`drops` specifically. Rather than reintroducing a known, already-diagnosed-and-fixed bug a third
time, V5 uses `DATETIMEOFFSET(6)` directly. `acquisition_date` is unaffected (`DATE`/`LocalDate`,
not Instant-mapped — ERD.md §2.9 explains this is intentional, a calendar date not a timestamp).

Chain verified: `V1 → V2 → V3 → V4 → V5`, no version collision. FKs to `users(id)`/`items(id)`
use the SQL Server default referential action (`NO ACTION`), matching ERD.md §3's Relationship
Summary for both `users↔drops` and `items↔drops`.

**Not run against a live database in this environment** — same caveat as Milestone 4's report:
no SQL Server/Azure SQL Edge instance available in this sandbox. Please verify Flyway applies V5
cleanly, same as prior milestones' manual verification step.

---

# 9. Tests Added

## `drop/service/DropServiceTest.java`

- `list_invalidPage_throwsValidation`, `list_sizeTooLarge_throwsValidation`,
  `list_sortFieldNotWhitelisted_throwsValidation`
- `list_dbSortField_delegatesPaginationToRepository` (DB-level sort path)
- `list_sortByCurrentValueUsd_sortsInMemoryAndPaginates`,
  `list_sortByCurrentValueUsd_priceUnavailableRowsSortLast` (in-memory sort path, §5)
- `getById_ownershipMismatch_throwsOwnershipMismatch`, `getById_found_returnsMappedResponse`
- `create_itemNotFound_throwsResourceNotFound`, `create_itemInactive_throwsResourceNotFound`,
  `create_validRequest_savesDropWithManualSourceAndReturnsPrice`
- `update_ownershipMismatch_throwsOwnershipMismatch`, `update_nullFields_leaveUnchanged`,
  `update_partialFields_onlySentFieldsChange`
- `delete_ownershipMismatch_throwsOwnershipMismatch`, `delete_found_softDeletesRow`

## `drop/controller/DropControllerTest.java`

- Every endpoint: happy path (200/201/204), `401` with no token, `404` ownership mismatch
  (explicitly asserted as NOT `403`)
- `GET /drops`: `422` for a non-whitelisted sort field, pagination `meta` shape
- `POST /drops`: `Location` header on `201`, `404` item-not-found/inactive, `422` for
  invalid `quantity` and a future `acquisitionDate`
- `PATCH /drops/{id}`: `422` for invalid `quantity`

Both test classes mirror the exact conventions established by `ItemServiceTest`/
`ItemControllerTest` (Milestone 3) and `PricingServiceTest`/`PriceControllerTest` (Milestone 4):
Mockito unit test for the service layer, `@WebMvcTest` + `@Import(SecurityConfig.class)` +
`RateLimitPropertiesTestConfig` boilerplate for the controller layer. Authenticated requests use
a small `asUser(Long userId)` helper built with `SecurityMockMvcRequestPostProcessors
.authentication(...)`, constructing the same `UsernamePasswordAuthenticationToken(userId, null,
authorities)` shape that `JwtAuthenticationFilter` produces in production — not
`.user("1").roles(...)` (that pattern, used for Item's ADMIN-only endpoints, produces a
`UserDetails` principal, which would bind as `null` to `DropController`'s
`@AuthenticationPrincipal Long userId` parameter and silently break every ownership-scoped test).

**As with every prior milestone's report from this environment: these tests have not actually
been executed here.** No Maven Central network access and no local `.m2` cache means the build
itself can't be compiled in this sandbox. Please run:

```powershell
mvn clean test
```

Expected: baseline **73 existing tests still PASS**, plus **12 new `DropServiceTest`** + **17
new `DropControllerTest`** = **29 new tests**, for **102/102 PASS, BUILD SUCCESS**. If anything
fails, please send back the output — that would be a real finding from this environment's lack
of a build step, not a scope change.

---

# 10. Manual Verification

Not performed, same reason as every prior milestone report — no running Azure SQL Edge/Redis
instance in this sandbox. Recommended manual pass once `mvn clean test` is green:

- `POST /drops` with a valid, active `itemId` (reuse an item from Milestone 3's catalog) → `201`
  + `Location` header; confirm `currentValueUsd` matches whatever Milestone 4's `item_prices`
  data already shows for that item (or `priceAvailable:false` if none exists yet).
- `POST /drops` with an inactive or nonexistent `itemId` → `404`.
- `GET /drops` as the same user → confirm the row appears; as a second user → confirm it does
  NOT appear (ownership).
- `GET /drops/{id}` / `PATCH /drops/{id}` / `DELETE /drops/{id}` on another user's drop (using a
  second test account) → `404`, not `403`, for all three.
- `DELETE` a drop, then `GET /drops/{id}` on it again → `404` (confirms soft-delete makes it
  invisible, not just removed from the list).
- `GET /drops?sort=currentValueUsd&sort=` variants and `?minValue=`/`?maxValue=` → confirm
  filtering hits `acquisitionValueUsd`, not the live price.

---

# 11. Known Limitations / Interpretation Flags

Following the same pattern as Milestones 3 and 4's handoff reports:

1. **`sort=currentValueUsd` is in-memory, not DB-level** (§5) — a deliberate MVP trade-off for a
   per-user-bounded dataset, not a locked contract detail. If holdings-per-user ever grows large
   enough for this to matter, this is the first place to revisit.
2. **`created_at`/`updated_at`/`deleted_at` use `DATETIMEOFFSET(6)`, not the `DATETIME2` written
   in ERD.md §2.7** (§8) — same well-established fix pattern as V2/V3/V4, documented inline
   everywhere it matters (migration, entity javadoc, this report).
3. **`users.email`/`password_hash` are still nullable in the actual V1 migration**, even though
   the current ERD.md states they should be `NOT NULL` now (Steam-only accounts no longer
   exist). This predates Milestone 5 entirely and is unrelated to `drop/` — flagging purely for
   visibility since it surfaced while cross-checking the ERD, not something Milestone 5 touched
   or needs to touch.
4. **Build/tests not executed in this environment** (§9) — the single biggest caveat, as with
   every report from this sandbox so far.

---

# 12. Scope-Creep Confirmation

Not implemented, per the locked scope:

- `portfolio/`, `alert/`, `notification/`, `admin/`, `scheduler/` — untouched
- Any Steam integration (login, linking, inventory API, sync) — none exists in this product at all
- Audit logging for drop CRUD — confirmed out of the 4-action MVP audit scope, not added
- Rate limiting specific to `/drops/*` — none added (none was locked for these endpoints)
- Dedup/duplicate detection for drops — confirmed not needed (100% manual, no Steam asset ID)
- Optimistic locking / version column / concurrency conflict mechanism for `PATCH`/`DELETE
  /drops/{id}` — explicitly not built, per PM decision §4.5

`item/` (Milestone 3) and `pricing/` (Milestone 4) were used strictly read-only — no changes to
either module's code.

---

# 13. Programmer Handoff

1. **Run `mvn clean test` locally first** — the one step this environment can't do. Report back
   any failures.
2. Verify Flyway applies `V5` cleanly on top of the existing `V1→V2→V3→V4` chain.
3. If `mvn clean test` passes at 102/102, this milestone can go through the same PM acceptance
   flow as Milestones 3 and 4.
4. Do not start `portfolio/` (or any other module) until PM explicitly authorizes it as its own
   milestone, per the established pattern.
