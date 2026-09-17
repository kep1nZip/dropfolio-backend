# Dropfolio — Milestone 4 Completion Report
## Pricing (Read Path) — Programmer & PM Context

**Project:** Dropfolio Backend
**Milestone:** 4 — Pricing (MarketPriceProvider abstraction + `GET /prices/{itemId}`)
**Status:** ✅ **IMPLEMENTED — AWAITING PM ACCEPTANCE**
**Date:** 2026-09-05

---

# 1. Implementation Status

All items in the approved Milestone 4 scope are implemented:

| # | Item | Status |
|---|---|---|
| 1 | `MarketPriceProvider` interface | ✅ Implemented (interface only, no concrete provider) |
| 2 | `item_prices` entity + repository + `V4__init_item_prices.sql` | ✅ Implemented |
| 3 | `GET /api/v1/prices/{itemId}` | ✅ Implemented |
| 4 | `PricingService` cache-aside read flow | ✅ Implemented |
| 5 | Response shape (`itemId, priceUsd, priceAvailable, provider, fetchedAt`) | ✅ Implemented, unchanged |
| 6 | Item-not-in-catalog (404) vs. item-with-no-price-row (200, unavailable) | ✅ Implemented, distinguished |
| 7 | Tests (PricingServiceTest, PriceControllerTest) | ✅ Implemented |

**Important caveat — could not run `mvn clean test` in this environment.** This sandbox has no
Maven Central network access (only a fixed allow-list of domains, none of which is
`repo.maven.apache.org`) and no pre-populated local `.m2` repository, so the automated build
could not be executed here the way it was on the Milestone 3 machine
(`F:\dropfolio-milestone3-item\backend`). Everything below (files added/modified, migration,
tests) was written and manually re-reviewed line-by-line against the existing Milestone 3
codebase's conventions and compiled types, but **`mvn clean test` has not actually been run
against this code**. Please run it locally before accepting:

```powershell
mvn clean test
```

Expected: baseline 64 existing tests still PASS, plus the new pricing tests (9 new: 6
`PricingServiceTest` + 3 `PriceControllerTest`), for **73/73 PASS, BUILD SUCCESS**. If anything
fails, that's a real finding from this environment's lack of a build step, not a change of
scope — please send back the failure output and I'll fix it.

---

# 2. Endpoint

## `GET /api/v1/prices/{itemId}`

- Public — no authentication (`SecurityConfig`: `HttpMethod.GET, "/api/v1/prices/*"` → `permitAll()`).
- **200** — price available:
```json
{ "success": true, "data": {
  "itemId": 5, "priceUsd": 0.52, "priceAvailable": true,
  "provider": "STEAM_MARKET", "fetchedAt": "2026-08-31T09:55:00Z"
}}
```
- **200** — price not yet synced (item exists, no `item_prices` row yet):
```json
{ "success": true, "data": {
  "itemId": 6, "priceUsd": null, "priceAvailable": false,
  "provider": null, "fetchedAt": null
}}
```
- **404** — item not in catalog at all (`ResourceNotFoundException`, reused from `item/`).

No other pricing endpoint exists (no history, no admin sync trigger).

---

# 3. Existing Components Reused

- `item/repository/ItemRepository` — `existsById(itemId)` used to distinguish 404 vs. 200-unavailable, per the approved assessment ("existing components yang akan digunakan").
- `common/cache/CacheKeys.priceItem(Long itemId)` — already existed (`"price:item:" + itemId`), unchanged, just consumed.
- `common/cache/RedisConfig` — existing `RedisTemplate<String, Object>` bean, unchanged.
- `common/envelope/ApiResponse`, `ErrorCategory`, `GlobalExceptionHandler` — unchanged, reused as-is.
- `common/exception/ResourceNotFoundException` — reused (no new exception type needed for the 404 case).
- `resilience4j.circuitbreaker.instances.redisCache` in `application.yml` — already existed (from Milestone 2/3 scaffolding), unchanged; `PricingService` looks it up by name from `CircuitBreakerRegistry`.

---

# 4. Cache Behavior

`PricingService.getPrice(itemId)`:

1. `itemRepository.existsById(itemId)` → false → `ResourceNotFoundException` → `404`.
2. Redis `GET price:item:{itemId}` (wrapped in the `redisCache` circuit breaker).
   - HIT → deserialize → return, **never touches Azure SQL**.
3. MISS → `itemPriceRepository.findTopByItemIdOrderByFetchedAtDesc(itemId)`.
   - SQL HIT → build `PriceResponse` from the row, `SET` it back into Redis with a 10-minute TTL (`Duration.ofMinutes(10)`, unchanged from spec), return.
   - SQL MISS → `priceAvailable=false, priceUsd=null, provider=null` (see §7 interpretation flag for `fetchedAt`), return. **Not written back to Redis as a negative/empty cache entry** — TECHNICAL_SPEC.md §6.2 only describes repopulation for the SQL-HIT branch ("Hasil dari langkah 3 di-SET kembali ke Redis"); step 4 (SQL MISS) has no repopulation instruction, so none was added. This means an unsynced item will re-hit SQL on every request until `PriceSyncJob` (a later milestone) writes its first row — flagged here rather than silently deviating from the locked steps by inventing negative caching.

TTL: **10 minutes**, unchanged (`CACHE_TTL = Duration.ofMinutes(10)` — a compile-time constant, not exposed as `application.yml` config, since TECHNICAL_SPEC.md §6.1 lists this as the fixed baseline and nothing in the approved scope asked for it to be configurable).

Never calls `MarketPriceProvider` from this read path — `PricingService`'s constructor has no dependency on it at all, so there is no code path by which it could.

---

# 5. Redis Circuit Breaker Behavior

`PricingService` wraps every `RedisTemplate` call (both the `GET` on read and the `SET` on
repopulation) through `CircuitBreakerRegistry.circuitBreaker("redisCache")`
(`executeSupplier`/`executeRunnable`):

- **Circuit CLOSED, Redis call succeeds** → normal cache-aside flow (§4).
- **Circuit CLOSED, Redis call throws** → exception caught, treated as a cache miss/failed
  repopulation, falls through to SQL. The failure is recorded by the circuit breaker itself
  (counts toward the failure-rate threshold already configured in `application.yml`).
- **Circuit OPEN** → `CallNotPermittedException` is thrown immediately by
  `executeSupplier`/`executeRunnable` **before `RedisTemplate` is touched at all** — verified in
  `PricingServiceTest#getPrice_redisCircuitBreakerOpen_skipsRedis_readsSqlDirectly` via
  `verify(redisTemplate, never()).opsForValue()`. Read falls straight to SQL.

Never calls the price provider as a Redis-down fallback (TECHNICAL_SPEC.md §6.3 / PRD §33 — API
storm prevention) — again structurally true since no provider dependency exists in this class.

---

# 6. MarketPriceProvider Abstraction

- `pricing/provider/MarketPriceProvider.java` — interface only, exact signature from
  SYSTEM_ARCHITECTURE.md §6 (`fetchPrice`, `isAvailable`, `providerName`).
- **No concrete implementation** (`SteamMarketProvider`, `PriceEmpireProvider`, etc.) — per PM
  approval, deferred to whenever `PriceSyncJob` is built.
- Nothing in `pricing/` currently references or injects `MarketPriceProvider` — it exists purely
  as a not-yet-consumed interface, which is by design for this milestone.

---

# 7. Migration

`V4__init_item_prices.sql` — follows ERD.md §2.6 exactly:

- Columns: `id, item_id, provider, price_usd, price_available, fetched_at, created_at` — no extra
  columns, no extra constraints.
- `price_usd DECIMAL(18,4) NULL` (nullable, per ERD).
- `FK_item_prices_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE` — matches
  ERD.md §3 Relationship Summary exactly (`items ↔ item_prices` = CASCADE).
- `IX_item_prices_item_fetched (item_id, fetched_at DESC)` — the exact index named in the
  approval message.
- No unique constraint on `(item_id, provider, fetched_at)` — ERD explicitly says this is
  intentional.
- `fetched_at`/`created_at` use `DATETIMEOFFSET(6)`, consistent with the V2/V3 Instant/Hibernate
  6 fix already established in this codebase.
- Chain verified: `V1__init_users_roles.sql → V2__fix_instant_datetime_columns.sql →
  V3__init_items.sql → V4__init_item_prices.sql`, no version number collision.

**Not run against a live database in this environment** (no SQL Server/Azure SQL Edge instance
available here) — please verify Flyway applies V4 cleanly on your machine, same as Milestone 3's
verification step.

---

# 8. Tests Added

## `pricing/service/PricingServiceTest.java` (6 tests)

- `getPrice_itemNotInCatalog_throwsResourceNotFound`
- `getPrice_redisCacheHit_returnsCachedValue_neverTouchesSql`
- `getPrice_redisMiss_sqlHit_buildsResponseAndRepopulatesCache`
- `getPrice_redisMiss_sqlMiss_returnsPriceUnavailable`
- `getPrice_redisCircuitBreakerOpen_skipsRedis_readsSqlDirectly`
- `getPrice_neverCallsMarketPriceProvider` (documents the structural invariant — no provider
  dependency exists in the class to begin with)

## `pricing/controller/PriceControllerTest.java` (3 tests)

- `getPrice_public_noAuthNeeded_priceAvailable_returns200`
- `getPrice_public_noAuthNeeded_priceUnavailable_returns200`
- `getPrice_itemNotInCatalog_returns404`

Both test classes mirror the exact conventions already established by `ItemServiceTest` /
`ItemControllerTest` (Mockito unit test for the service, `@WebMvcTest` + `@Import(SecurityConfig.class)`
contract test for the controller, same `RateLimitPropertiesTestConfig`/Redis-stubbing boilerplate
for the rate-limit filter).

**As noted in §1, these tests have not actually been executed in this environment.** They were
written and reviewed for correctness against the real method signatures in this codebase
(`RedisTemplate`, `ValueOperations`, `CircuitBreaker`/`CircuitBreakerRegistry` from
`resilience4j-circuitbreaker:2.2.0`, already a project dependency), but please treat "PASS" as
unverified until `mvn clean test` is run.

---

# 9. Manual Verification

Not performed — this environment has no running Azure SQL Edge/Redis instance and the build
itself couldn't be compiled (§1), so there was nothing to run Postman against. Recommend
repeating the Milestone 3-style manual verification once `mvn clean test` passes locally:

- `GET /prices/{id}` for an item with a manually-inserted `item_prices` row → `200`, correct shape.
- `GET /prices/{id}` for an item with no `item_prices` row → `200`, `priceAvailable=false`.
- `GET /prices/{id}` for a nonexistent item → `404`.
- Stop Redis, repeat the first case → still `200` (SQL fallback), confirms circuit breaker path in a real environment.

---

# 10. Known Limitations / Interpretation Flags

Following the same pattern as Milestone 3's handoff §22 ("Potential Interpretation Flag"):

1. **`fetchedAt: null` on SQL MISS.** TECHNICAL_SPEC.md §6.2 step 4 locks
   `priceAvailable=false, priceUsd=null, provider=null` for the SQL-miss case but doesn't
   address `fetchedAt`. Since no fetch has ever happened for an unsynced item, `null` was used
   rather than a fabricated timestamp. This is the one genuinely non-locked detail in the whole
   response shape; flagging it explicitly rather than treating it as self-evidently settled.
2. **No negative caching on SQL MISS.** Per the locked cache-aside steps (§4 above), only the
   SQL-HIT branch repopulates Redis. An item with no price row yet will re-query SQL on every
   request until `PriceSyncJob` populates it. This is a direct, literal reading of the locked
   steps, not an optimization choice — flagging so it isn't mistaken for an oversight later.
3. **`ProviderPriceResult` shape.** The `MarketPriceProvider.fetchPrice(...)` return type's
   field set isn't specified anywhere outside the interface signature itself. A minimal
   3-field record (`priceAvailable, priceUsd, fetchedAt`) was created purely so the locked
   interface compiles; it is not consumed by anything in this milestone and can be revised
   freely whenever `PriceSyncJob`/a concrete provider is actually built.
4. **Build/tests not executed in this environment** (§1) — the single biggest caveat on this
   report. Everything else above assumes a clean `mvn clean test` run confirms what manual
   review already indicates, but that confirmation is outstanding.

---

# 11. Scope-Creep Confirmation

Not implemented, per the Hard Scope Freeze:

- `PriceSyncJob`, scheduler, distributed price-sync lock
- `POST /admin/sync-jobs/price-sync`, admin sync functionality
- Concrete price provider (Steam Market, PriceEmpire, or any other)
- Price history endpoint
- Active-only item filtering (Milestone 3's locked `isActive` behavior untouched)
- Marketplace/trading, WebSocket, subscription/payment, bot integration
- Retention/archiving policy for `item_prices`
- Any pricing endpoint beyond `GET /prices/{itemId}`

`item/` module (Milestone 3) was not modified except for the one `SecurityConfig` addition
required to expose the new public route — no behavior change to any existing `items` endpoint.

---

# 12. Files Added / Modified

## Added

```text
pricing/provider/MarketPriceProvider.java
pricing/provider/ProviderPriceResult.java

pricing/entity/ItemPrice.java

pricing/repository/ItemPriceRepository.java

pricing/dto/PriceResponse.java

pricing/service/PricingService.java

pricing/controller/PriceController.java

src/main/resources/db/migration/V4__init_item_prices.sql

pricing/service/PricingServiceTest.java
pricing/controller/PriceControllerTest.java
```

## Modified

```text
common/security/SecurityConfig.java   (added GET /api/v1/prices/* -> permitAll())
```

No other files touched.

---

# 13. Programmer Handoff

1. **Run `mvn clean test` locally first** — this is the one step this environment could not do.
   Report back any compile or test failures so they can be fixed before PM acceptance.
2. Verify Flyway applies `V4` cleanly on top of the existing `V1→V2→V3` chain.
3. If `mvn clean test` passes with 73/73, this milestone can go through the same PM
   acceptance flow Milestone 3 used.
4. Do not start on `PriceSyncJob`/scheduler/admin sync until PM explicitly authorizes that as
   its own milestone, per the established pattern.
