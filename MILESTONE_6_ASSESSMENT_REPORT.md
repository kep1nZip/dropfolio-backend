# MILESTONE 6 ASSESSMENT REPORT — AWAITING PM APPROVAL
## `portfolio/` Module — Summary, Breakdown, Export

**Status:** 📋 ASSESSMENT ONLY — NO CODE/MIGRATION/IMPLEMENTATION CHANGES MADE
**Date:** 7 September 2026
**Based on:** `PRD.md` v2.0, `SYSTEM_ARCHITECTURE.md` v2.0, `ERD.md` v2.0, `API_CONTRACT.md` v2.1, `TECHNICAL_SPEC.md` v2.0 (updated during Milestone 5), `CLAUDE_CONTEXT.md` v2.1, plus the actual `drop/`/`item/`/`pricing/` code from Milestones 3–5.

---

# 1. Scope & Non-Scope

## In-scope (per PM message + `API_CONTRACT.md` §7)

| Endpoint | Auth | Ownership |
|---|---|---|
| `GET /api/v1/portfolio/summary` | Required | self (implicit — current user only, no resource ID) |
| `GET /api/v1/portfolio/breakdown` | Required | self |
| `GET /api/v1/portfolio/export` | Required | self |

Confirmed **still locked** in `API_CONTRACT.md` v2.1 §7 — none of the three were removed or
altered by the Steam/manual-inventory architecture change. `portfolio/` has **no database table
of its own** (`ERD.md` has no `portfolio` entity anywhere) — everything here is computed on the
fly from `drops` + `items` + `item_prices`, exactly like `pricing/`'s read path. This means:
**no migration, no new Redis keys, no new persistence** for this milestone (§9/§10 below).

## Non-scope (confirmed out)

- `alert/`, `notification/`, `admin/`, `scheduler/` — untouched.
- Any Steam integration — none exists in this product.
- IDR / exchange-rate conversion — PRD.md §17 explicitly defers this ("berdasarkan
  exchange-rate strategy yang **nantinya ditentukan**"); the locked `GET /portfolio/summary`
  JSON example only has `totalValueUsd`, no IDR field. Not building this now.
- Historical portfolio growth chart, growth %, profit contribution, category performance — PRD.md
  §24 explicitly Post-MVP.
- PDF export — PRD.md §23 explicitly Post-MVP ("bukan bagian core value proposition").
- Price history endpoint (`GET /prices/{itemId}/history`) — separate, unrelated, still Post-MVP
  per earlier milestones' findings.
- Async job / background processing for CSV export — `API_CONTRACT.md` §7 explicitly locks this
  synchronous for MVP ("ukuran data per-user kecil, tidak perlu job async").
- Audit logging for any portfolio read — these are read-only GETs, not in the 4-action MVP audit
  scope (same reasoning as Milestone 5's drop CRUD).
- Rate limiting specific to `/portfolio/*` — none locked in `API_CONTRACT.md` §12, none added.

---

# 2. Endpoint Contracts (From `API_CONTRACT.md` §7, Not Rewritten Freely)

## `GET /portfolio/summary`
```json
{ "totalValueUsd": 125.42, "totalItems": 47,
  "highestValueItem": { "itemId": 5, "name": "Revolution Case", "valueUsd": 12.30 },
  "latestDrop": { "itemId": 8, "name": "USP-S Skin", "acquiredAt": "2026-08-30" },
  "weeklyDrop": { "caseCount": 1, "skinOrGraffitiCount": 1, "estimatedValueUsd": 3.21 },
  "itemsWithUnavailablePrice": 2 }
```
No query params. Synchronous, cache-aside read only (never calls the provider directly —
reuses `PricingService`).

## `GET /portfolio/breakdown`
- Query params: `search`, `type` (`CASE|SKIN|GRAFFITI`), `page`, `size`, `sort` (whitelist:
  `totalValueUsd`, `quantity`, `name`).
- Rows are **aggregated per distinct item** — "digabung per-item (agregat quantity dari beberapa
  `drops` row dengan `item_id` sama)" (`API_CONTRACT.md` §7, verbatim).
```json
{ "itemId": 5, "name": "Revolution Case", "type": "CASE", "totalQuantity": 10,
  "currentPriceUsd": 0.52, "priceAvailable": true, "totalValueUsd": 5.20 }
```

## `GET /portfolio/export`
- `Content-Type: text/csv`, columns per PRD.md §23: **Item, Type, Acquired Date, Quantity,
  Acquisition Price, Current Price, Current Value**.
- `200` with the file, or `204` if the user has no data.
- Column list includes a per-row "Acquisition Price" — a field that only exists at the
  individual-drop level (`drops.acquisition_value_usd`), not at the aggregated-per-item level.
  This means **export is one row per `drops` entry, NOT aggregated per item like breakdown is**.
  This isn't stated in so many words anywhere, but it's the only reading consistent with the
  locked column list — flagged as my working interpretation (§15) rather than something I'll
  silently assume without saying so.

---

# 3. Dependency on `item/`, `pricing/`, and `drop/`

`SYSTEM_ARCHITECTURE.md` §2 states only "`portfolio` bergantung pada `item` + `pricing`". This
sentence **predates the `drop/` module existing as its own concept** (same gap already noted in
the Milestone 5 assessment — `drop/` doesn't appear anywhere in `SYSTEM_ARCHITECTURE.md`'s
package tree or dependency rules, even though it clearly exists per `CLAUDE_CONTEXT.md` and
`API_CONTRACT.md`). Since `portfolio/` aggregates **manual holdings**, and manual holdings live
exclusively in `drops` (Milestone 5), `portfolio/` **must** also depend on `drop/` — there is no
other source for "what does this user own". This isn't ambiguous, just an obvious gap-fill,
consistent with how `drop→pricing` was handled last milestone (necessary, not prohibited by any
stated rule, not treated as scope creep).

Concretely: `PortfolioService` will call `DropRepository` (read-only), `ItemRepository`
(read-only, batch `findAllById`, same pattern as `drop/`), and `PricingService.getPrice(itemId)`
(read-only, same reuse pattern as `drop/` — one call per **distinct** itemId, not per row).

**One small but real addition needed to `drop/`:** `DropRepository` currently only has
`findByIdAndUserIdAndDeletedAtIsNull` (single-row, ownership-scoped). Portfolio needs "give me
**all** of this user's non-deleted drops" — a `findAllByUserIdAndDeletedAtIsNull(Long userId)`
query method. This is a **pure addition**, not a modification of any existing method signature,
endpoint, or behavior — same category of change as nothing needing to touch `pricing/` at all in
Milestone 5. Flagging it here for visibility per the same transparency standard used before.

---

# 4. Entities / DTOs / Repository / Service / Controller Needed

```text
(no new entity — computed from existing Drop/Item/ItemPrice, no new table)

drop/repository/DropRepository.java        — ADD: findAllByUserIdAndDeletedAtIsNull(Long userId)
                                              (additive only, see §3)

portfolio/dto/PortfolioSummaryResponse.java     — totalValueUsd, totalItems,
                                                   highestValueItem?, latestDrop?,
                                                   weeklyDrop, itemsWithUnavailablePrice
portfolio/dto/HighestValueItemRef.java          — itemId, name, valueUsd (nested, own type —
                                                   TECHNICAL_SPEC.md §3.12: never shared with
                                                   DropItemRef/AlertItemRef)
portfolio/dto/LatestDropRef.java                — itemId, name, acquiredAt
portfolio/dto/WeeklyDropStats.java              — caseCount, skinOrGraffitiCount,
                                                   estimatedValueUsd
portfolio/dto/PortfolioBreakdownItemResponse.java — itemId, name, type, totalQuantity,
                                                     currentPriceUsd, priceAvailable,
                                                     totalValueUsd

portfolio/mapper/PortfolioMapper.java           — explicit mapping, no reflection (TECHNICAL_SPEC §2)

portfolio/service/PortfolioService.java         — getSummary(userId), getBreakdown(userId, ...),
                                                   exportCsv(userId) — all business logic here

portfolio/controller/PortfolioController.java   — bind + call + wrap only
```

**No `SecurityConfig` change needed** — `/api/v1/portfolio/*` isn't public, default
`anyRequest().authenticated()` already covers it (same as `drop/`).

---

# 5. Ownership & User-Scoping

Unlike `item/`, `drop/` (or later `alert/`), **portfolio has no per-resource ID at all** — there
is no "portfolio #42" to fetch, no way to reference someone else's portfolio by ID in the URL.
Every portfolio endpoint is inherently and only "the current authenticated user's own
aggregate", scoped via `@AuthenticationPrincipal Long userId` exactly like `drop/`, then passed
straight into `dropRepository.findAllByUserIdAndDeletedAtIsNull(userId)`. There is **no
ownership-mismatch 404 case possible here** — `TECHNICAL_SPEC.md` §16.2 point 5's "ownership:
self → test resource milik user lain → 404" doesn't apply the same way, since there's no ID path
parameter to substitute another user's ID into. I'm flagging this explicitly so it isn't assumed
missing from the test plan (§12) by mistake.

---

# 6. Validation Rules

| Field | Rule |
|---|---|
| `breakdown.page`/`size` | Same `page>=1`, `size 1..100` convention as `drop`/`item` |
| `breakdown.sort` | Whitelist `totalValueUsd`, `quantity`, `name` — reject with `422` (reused pattern) |
| `breakdown.type` | Valid `ItemType` enum only — reused `MethodArgumentTypeMismatchException` handling |

No new exception classes needed. `summary`/`export` take no query params, so no request-level
validation exists for them at all.

---

# 7. Error Handling

| Scenario | Status |
|---|---|
| No token, any endpoint | `401` (implicit, filter chain) |
| `breakdown` invalid `sort`/`type`/`page`/`size` | `422 VALIDATION_ERROR` (reused) |
| `export` with zero drops | `204 No Content` |
| Everything else | `200` |

No `404` case exists for any of the three endpoints (§5). No new error codes needed.

---

# 8. Pagination / Sorting (Breakdown Only)

Same mechanics as `drop/`: `page`/`size` validated the same way, `sort` whitelist enforced the
same way. **Technical complexity note (not a locked-contract ambiguity, purely how it's built):**
breakdown rows are aggregated per item (`GROUP BY item_id`, summing `quantity` across drop rows)
*and* two of the three whitelisted sort fields (`totalValueUsd`, and arguably `quantity` once
aggregated) depend on that aggregation. Rather than writing a SQL-level `GROUP BY` + join to
"latest price per item" (the same kind of complexity deliberately avoided for `drop/`'s
`minValue`/`maxValue` filter and `sort=currentValueUsd` in Milestone 5), the plan is to reuse the
exact same MVP-scale approach already established: fetch the user's filtered (search/type),
non-deleted drops via `DropRepository`, aggregate per `itemId` in Java, resolve each distinct
item's current price via `PricingService` (same batching-by-distinct-id as `drop/`), then
sort/paginate the aggregated list in Java. This is a direct continuation of Milestone 5's
established pattern, not a new one — flagged here for visibility, not because it's in question.

**Minor doc inconsistency noticed (not blocking, see §15 point 6):** the sort whitelist names the
field `quantity`, but the response field is `totalQuantity`. I'll treat `sort=quantity` as
sorting by `totalQuantity` (there's no other quantity-shaped field it could mean), but wanted
this naming mismatch on record rather than silently reconciled.

---

# 9. Database / Migration Impact

**None.** `ERD.md` has no `portfolio` table — confirmed by full-text search, the only two
`portfolio`-related ERD mentions are index comments explaining that `IX_drops_item_id` and
`IX_item_prices_item_fetched` exist specifically *to support* portfolio valuation queries (both
already created, in V5 and V4 respectively — nothing new needed). No Flyway migration for this
milestone.

---

# 10. Redis / Pricing Impact

**None new.** `CLAUDE_CONTEXT.md` §5 already documents `GET /portfolio/*` as "cache-aside read
only" — meaning `portfolio/` never talks to Redis or the price provider directly, it exclusively
calls `PricingService.getPrice(itemId)` and lets Milestone 4's existing cache-aside/circuit
breaker logic do what it already does. No new Redis keys, no TTL changes, no changes to
`pricing/` itself.

---

# 11. Security Implications

All three endpoints: `Auth: Required`, no `ADMIN` role, no new public surface, no
`SecurityConfig` change (§4). The only security-relevant nuance is §5 above (no per-resource
ownership-mismatch case exists for this module, unlike every prior CRUD module).

---

# 12. Test Plan

**`PortfolioServiceTest` (unit, Mockito):**
- `summary`: empty portfolio (zero drops) → `totalValueUsd=0, totalItems=0, highestValueItem=null,
  latestDrop=null, itemsWithUnavailablePrice=0`; mixed available/unavailable prices → correct
  exclusion from `totalValueUsd` + correct `itemsWithUnavailablePrice` count (pending §15 point 2
  decision on what's being counted); `highestValueItem`/`latestDrop` selection logic; weekly-drop
  window logic (pending §15 point 4 decision).
- `breakdown`: aggregation correctness (multiple `drops` rows, same `itemId`, summed quantity);
  `search`/`type` filtering; sort by each of the 3 whitelisted fields; pagination; price
  unavailable → `totalValueUsd=null` (consistent with `drop/`'s established null-on-unavailable
  convention).
- `export`: CSV content/column order matches PRD §23 exactly; zero-drops → signal for controller
  to return `204`; unavailable-price row rendering (pending §15 point 7 decision).
- **No ownership-mismatch test** — not applicable (§5).

**`PortfolioControllerTest` (MockMvc + `@Import(SecurityConfig.class)`):**
- Every endpoint: happy path, `401` with no token.
- `breakdown`: `422` for bad `sort`/`type`, pagination `meta` shape.
- `export`: `Content-Type: text/csv`, `Content-Disposition` header, `204` for no data.

**Regression:** baseline **107 existing tests** (per Milestone 5's accepted result) must stay
green, plus all new Milestone 6 tests.

---

# 13. Existing Code Reused

| Component | Source | Use |
|---|---|---|
| `DropRepository` (+ 1 new method, §3) | Milestone 5 | Fetch user's holdings |
| `ItemRepository.findAllById` | Milestone 3/5 | Batch-resolve item name/type/etc. |
| `PricingService.getPrice(itemId)` | Milestone 4 | Current price per distinct item, cache-aside as-is |
| `GlobalExceptionHandler` | Milestone 2/3 | `422`/`401` generic handling |
| `ValidationException` | Milestone 3/4/5 | `breakdown` pagination/sort validation |
| `@AuthenticationPrincipal Long userId` | Milestone 2 (JWT filter) | Current user, same as `drop/` |

---

# 14. Potential Conflicts with Milestones 2–5

**None expected.** The only file outside a brand-new `portfolio/` package that needs touching is
`DropRepository.java` (one additive method, §3) — no existing method signature, endpoint, or
behavior changes. `item/`/`pricing/` remain fully read-only dependencies, untouched.

---

# 15. Items Requiring PM Decision (Escalation — `CLAUDE_CONTEXT.md` §10)

Portfolio is the first module so far where the **business-logic calculation itself**, not just
an isolated field or query param, is underspecified in the locked documents. `TECHNICAL_SPEC.md`
locked the DTO *shapes* precisely (§3.7) but not the arithmetic behind several of their values.
Per the Escalation Protocol, I'm not guessing on these:

### 1. `weeklyDrop` — what window counts as "this week"? — **`DISCREPANCY / PM DECISION REQUIRED`**
Not defined anywhere: calendar week (and if so, which day does it start/reset — Monday? Some
CS2/Valve-aligned day, since "weekly drop" is literally CS2's own in-game weekly item mechanic
per PRD.md §1/§49?) vs. a rolling 7-day window from the current request time. This materially
changes the numbers returned, not just an edge case.

### 2. `totalItems` — what exactly is being counted? — **`DISCREPANCY / PM DECISION REQUIRED`**
Three defensible readings: (a) sum of `quantity` across all of a user's drops (total physical
items owned), (b) count of distinct `itemId`s held (== number of `breakdown` rows), or (c) count
of individual `drops` rows (acquisition events). The JSON example (`"totalItems": 47`) doesn't
disambiguate. This is a headline dashboard number (PRD.md §18 "total items" stat) — worth getting
right rather than guessing.

### 3. `itemsWithUnavailablePrice` — items or drop rows? — **`DISCREPANCY / PM DECISION REQUIRED`**
Given price is an item-level attribute (not per-drop), I lean toward "count of distinct items
with no available current price" — but this is coupled to decision #2's answer (if `totalItems`
counts drop rows rather than distinct items, this field's unit should probably match). Flagging
together rather than deciding one in isolation.

### 4. `weeklyDrop.estimatedValueUsd` — current price or acquisition price? — **`DISCREPANCY / PM DECISION REQUIRED`**
"Estimated Value" of what was dropped this week could mean "what it's worth now" (current
market price, consistent with how the rest of portfolio values everything) or "what it was
recorded as worth when acquired" (`acquisitionValueUsd`, a record of that week's activity as
logged). I lean current-price for internal consistency with the rest of the module, but this
isn't stated anywhere.

### 5. `weeklyDrop.caseCount`/`skinOrGraffitiCount` — drops or quantity? — **`DISCREPANCY / PM DECISION REQUIRED`**
Does a single drop entry with `quantity: 5` (5 Cases in one manual entry) count as `caseCount: 5`
or `caseCount: 1` (one acquisition event)? PRD.md §18's mockup ("1 Case, 1 Skin/Graffiti") doesn't
disambiguate at that scale.

### 6. `breakdown` has no price-range filter, but PRD.md §19 says one should exist
PRD.md §19 (Search & Filter, MVP) lists `type`, `date`, and **`price range`** as the three MVP
filter dimensions — but the locked `API_CONTRACT.md` §7 `breakdown` query params only include
`search`/`type`/`page`/`size`/`sort`, no `minValue`/`maxValue`/date-range at all (unlike `drops`,
which does have `minValue`/`maxValue` + `dateFrom`/`dateTo`). This is a real gap between PRD and
the locked contract for this specific endpoint. Not blocking (I can implement exactly what
`API_CONTRACT.md` §7 currently locks with zero ambiguity), but flagging since it looks like an
omission rather than a deliberate scope cut, and the PM may want it added to the contract now
while `portfolio/` is being built rather than as a later revision.

### 7. Export CSV — unavailable-price row rendering (minor, not blocking)
When `priceAvailable=false` for a row, what goes in the "Current Price"/"Current Value" CSV
cells — blank, `N/A`, or the literal phrase `"Price unavailable"` (matching PRD.md §45 Principle
#1's own exact wording)? I'll default to `"Price unavailable"` for both cells unless told
otherwise — flagging rather than silently picking, since it's a visible, PM-decidable product
choice, not a technical one.

### 8. `highestValueItem`/`latestDrop` tie-breaking (minor, not blocking)
Two items with identical total value, or two drops on the identical `acquisitionDate` — which
wins? I'll default to "first by `itemId` ascending" and "most recent `acquisitionDate`, ties
broken by most recent `createdAt`" respectively unless told otherwise — genuinely low-stakes,
flagging for completeness only.

**I will proceed with my stated defaults for items #7 and #8 unless PM says otherwise (they're
minor enough not to block implementation), but items #1–#6 change the substantive numbers the
dashboard shows and I'm stopping on those specifically, per the Escalation Protocol's own
guidance not to guess on anything that changes user-facing output.**

---

# 16. Confirmation of No Scope Creep

- No endpoint added beyond the 3 already locked in `API_CONTRACT.md` §7.
- No change to `item/`, `pricing/`, or any existing `drop/` endpoint/behavior — the one
  `DropRepository` addition (§3) is net-new, not a modification.
- No `alert/`, `notification/`, `admin/`, `scheduler/`, or Steam-anything implemented.
- No IDR conversion, no historical charting, no PDF export, no async export job — all
  confirmed Post-MVP/out of scope per PRD.md.
- No audit logging added (read-only endpoints, outside the 4-action MVP scope).
- No rate limiting added (none locked for this domain).

---

**Waiting on PM decisions for §15 items #1–#6 (the calculation-semantics discrepancies) before
starting implementation. Items #7–#8 have stated defaults and won't block starting, but are
listed for visibility.**

**STOP — no code, migration, or implementation file has been created or modified at this stage.**
