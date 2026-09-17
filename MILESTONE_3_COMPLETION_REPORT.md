# Dropfolio Backend — Milestone 3 Completion Report

**Milestone:** Item Catalog (`item/`)
**Status:** Implementation complete, **not yet build/test-verified** (no Maven Central/network access in Claude's working environment — please run `mvn clean test` on your machine)

---

## 1. Files Created

**`item/` module:**
- `entity/Item.java`, `ItemType.java`
- `repository/ItemRepository.java`, `ItemSpecifications.java` (explicit dynamic query builder for `search`/`type` filters)
- `dto/ItemResponse.java`, `CreateItemRequest.java`, `UpdateItemRequest.java`
- `mapper/ItemMapper.java`
- `service/ItemService.java`
- `controller/ItemController.java`

**Database:**
- `src/main/resources/db/migration/V3__init_items.sql`

**Common (new exception):**
- `common/exception/ItemMarketHashNameConflictException.java`

**Tests:**
- `item/service/ItemServiceTest.java` (11 cases)
- `item/controller/ItemControllerTest.java` (12 cases, contract-level MockMvc)

## 2. Files Modified

| File | Change | Reason |
|---|---|---|
| `common/exception/GlobalExceptionHandler.java` | Added handlers for `HttpMessageNotReadableException` (invalid enum in body) and `MethodArgumentTypeMismatchException` (invalid enum in query param) | **Bug found, not scope creep.** Both previously fell through to the generic `Exception` handler → wrongly returned 500 instead of 422. Latent since Milestone 1 (no request DTO had an enum field until `CreateItemRequest.type`). General `common/` fix. |
| `common/security/SecurityConfig.java` | Added path matchers: `GET /api/v1/items`, `GET /api/v1/items/{id}` → `permitAll()`; `POST /api/v1/items`, `PATCH /api/v1/items/{id}` → `hasRole("ADMIN")` | Required wiring for this milestone's endpoints |

No file inside `auth/`, `user/`, or any other already-accepted module was touched. Milestone 1 and Milestone 2 tests were not modified.

## 3. Endpoints Implemented

| Endpoint | Auth | Status codes implemented |
|---|---|---|
| `GET /api/v1/items` | Public | 200, 422 (invalid `sort`, invalid `type`, `page`/`size` out of bounds) |
| `GET /api/v1/items/{id}` | Public | 200, 404 |
| `POST /api/v1/items` | ADMIN | 201 (with `Location` header), 401, 403, 409, 422 |
| `PATCH /api/v1/items/{id}` | ADMIN | 200, 401, 403, 404, 422 |

No endpoint added beyond these 4. No HTTP method changed. No DELETE endpoint created — `isActive` toggle via `PATCH` only, per hard constraint #15.

## 4. Validation Added

- `CreateItemRequest`: `name` (not blank, ≤200), `type` (not null, enum), `marketHashName` (not blank, ≤300; DB-backed uniqueness check in service, not an annotation — a `@Unique`-style annotation can't safely express "unique in the database" without a live query)
- `UpdateItemRequest`: all fields optional, `null` = "leave unchanged"; `name`/`iconUrl` length-capped when present
- `GET /items` query params: `sort` validated against the exact whitelist locked in API_CONTRACT.md §4 (`name`, `createdAt` only) → `ValidationException` (422) if violated; `page < 1` or `size` outside `[1, 100]` → same

## 5. Authorization / Ownership Behavior

- Items are a **global catalog, not ownership-scoped** (no `userId` on the entity per ERD.md §2.5) — the `findByIdAndUserId(...)` ownership pattern doesn't apply here, correctly not used.
- `POST`/`PATCH` enforcement is **role-based only** (ADMIN), done at `SecurityConfig` path-matcher level — same single-layer convention already established by `auth/`'s endpoints (deliberately not duplicated via `@PreAuthorize` to avoid a second, potentially-inconsistent enforcement mechanism).
- Unauthenticated request to `POST`/`PATCH` → 401. Authenticated-but-non-ADMIN → 403 (never 404 — 404-for-ownership-mismatch is a different pattern that doesn't apply to a non-ownership-scoped resource like items).

## 6. Audit Behavior

- `ItemService.create()` → `@Auditable(action = "ADMIN_CREATE_ITEM")`
- `ItemService.update()` → `@Auditable(action = "ADMIN_UPDATE_ITEM")`

Both action names are exactly the two locked values from CLAUDE_CONTEXT.md's 4-action audit list. `AuditAspect` is still the Milestone 1 skeleton (logs only, doesn't persist to `audit_logs` yet) — unchanged this milestone, since that table is out of `item/`'s scope (lands with `admin/`).

## 7. Tests Added

23 new test cases across 2 files (§1). **Not executed** — same network limitation as Milestone 2. Please run:

```bash
mvn clean test
```

and confirm all Milestone 1 + 2 + 3 tests stay green together.

## 8. Schema/Migration Impact

New migration **`V3__init_items.sql`** — creates only the `items` table (ERD.md §2.5: `id, name, type, market_hash_name, icon_url, is_active, created_at, updated_at`), nothing else. No table outside `item/`'s scope was created (hard constraint #8 respected).

**One proactive fix applied:** `created_at`/`updated_at` are declared `DATETIMEOFFSET(6)` directly in `V3`, not `DATETIME2` — applying the same fix your `V2` already made for `users`/`user_roles` (Hibernate 6.5 maps `java.time.Instant` → `DATETIMEOFFSET`, not `DATETIME2`) from the start, so `items` doesn't need its own follow-up migration for the same issue.

**Please double check the version number:** I numbered this `V3` assuming your Flyway history is exactly `V1` (users/roles/user_roles) then `V2` (Instant/DATETIMEOFFSET fix) — matching what I found in the project you uploaded. If your actual next-available version differs, rename the file before running.

## 9. Discrepancy / Interpretation Flags

**One resolved via cross-reference, not a stop:** `POST /items` 409 conflict (duplicate `marketHashName`) has no domain-specific error `code` in the API_CONTRACT.md §0.6.2 registry. That section's own fallback rule is explicit: an error without a registry row **must** use `code = category`. So `ItemMarketHashNameConflictException` sets `code = "CONFLICT"` (identical to its category), not an invented code like `ITEM_MARKET_HASH_NAME_ALREADY_EXISTS`. This isn't an ambiguity requiring your decision — the fallback rule already answers it — but flagging so you can see the reasoning.

**One low-risk interpretation, flagged for your awareness:** `GET /items`'s locked query param list (`search`, `type`, `page`, `size`, `sort`) has no `isActive` filter, and `ItemResponse` includes the `isActive` field in its shape. I read this as: **the public catalog returns items regardless of active status**, letting the client decide how to display an inactive one (e.g. a "discontinued" badge), rather than silently hiding them — since hiding would be an unstated filter I'd be inventing. If you intended inactive items to be hidden from public listing by default, tell me and I'll add that as a one-line change (`.and(ItemSpecifications.activeOnly())` when no explicit admin context) — I did not implement any such default filtering without your confirmation.

## 10. Scope Creep Confirmation

- No endpoint added beyond the 4 locked ones.
- No HTTP method changed.
- No request/response shape changed from TECHNICAL_SPEC.md §3.4.
- No status code changed.
- No new error **code** added — the one new exception class deliberately reuses `code = category` per the registry's own fallback rule (§9 above), not a new registry entry.
- No ownership semantics touched (items aren't ownership-scoped, correctly not forced into that pattern).
- No table created outside `items` (ERD.md §2.5 scope only).
- No other module (`pricing/`, `drop/`, `steam/`, etc.) implemented or scaffolded ahead of its turn.
- The two `GlobalExceptionHandler` additions and the `SecurityConfig` matcher additions are the only touches outside `item/` itself — both are bug fixes / required wiring, not improvements or scope expansion.

---

**Awaiting your review/acceptance before proceeding to Milestone 4.**
