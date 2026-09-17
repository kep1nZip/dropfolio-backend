# Dropfolio — Milestone 6 Completion Report
## `portfolio/` Module — Summary, Breakdown, Export

**Project:** Dropfolio Backend
**Milestone:** 6 — Portfolio (Summary / Breakdown / Export)
**Status:** ✅ **IMPLEMENTED — AWAITING PM ACCEPTANCE**
**Date:** 7 September 2026

---

# 1. Endpoints Implemented

| Endpoint | Auth | Ownership |
|---|---|---|
| `GET /api/v1/portfolio/summary` | Required | self (implicit — no resource ID) |
| `GET /api/v1/portfolio/breakdown` | Required | self |
| `GET /api/v1/portfolio/export` | Required | self |

No `SecurityConfig` change needed — default `anyRequest().authenticated()` already covers
`/api/v1/portfolio/*`, same as `drop/`. No migration — `portfolio/` has no table of its own,
everything is computed from `drops` + `items` + `item_prices` at read time.

---

# 2. Files Added

```text
portfolio/dto/PortfolioSummaryResponse.java
portfolio/dto/HighestValueItemRef.java
portfolio/dto/LatestDropRef.java
portfolio/dto/WeeklyDropStats.java
portfolio/dto/PortfolioBreakdownItemResponse.java

portfolio/service/PortfolioService.java

portfolio/controller/PortfolioController.java

common/config/ClockConfig.java   (see §6 — testability seam for the weekly-window rule)

portfolio/service/PortfolioServiceTest.java
portfolio/controller/PortfolioControllerTest.java
```

## Files Modified

```text
drop/repository/DropRepository.java   — added findAllByUserIdAndDeletedAtIsNull(Long userId)
```

Purely additive — no existing method signature, endpoint, or behavior in `drop/`, `item/`, or
`pricing/` was changed. No `SecurityConfig` change.

---

# 3. How Each Locked PM Decision Was Implemented

### 1. `weeklyDrop` window — Wednesday 01:00 UTC, fixed, never rolling/local-tz
`PortfolioService.currentWeekBounds(Instant now)` finds the most recent Wednesday, sets it to
01:00 UTC, and if that instant is still in the future relative to `now` (i.e. `now` is on
Wednesday but before 01:00 UTC), steps back one more week — so a drop dated exactly "Wednesday"
doesn't count as "this week" until the clock actually passes 01:00 UTC that day. Verified in
`PortfolioServiceTest#getSummary_weeklyDrop_boundaryRespectsUtcHourNotJustDate`, the specific
edge case that distinguishes this from a naive "any Wednesday = new week" implementation.

Since `acquisitionDate` has no time component (`LocalDate`, by design — ERD.md §2.9), the UTC
instant boundaries are converted to plain calendar dates (`[weekStartDate, weekEndDate)`, always
exactly 7 calendar days) and compared directly — this is the only way to apply a time-of-day
rule to a field that doesn't carry time-of-day information; documented inline in the code.

### 2. `totalItems` — sum of `quantity`, not distinct items or drop rows
`drops.stream().mapToInt(Drop::getQuantity).sum()` — verified in
`getSummary_totalItems_isSumOfQuantityNotDistinctItemsOrRows` (3 + 5 + 2 across 2 distinct items
and 3 drop rows → `10`, not `2` or `3`).

### 3. `itemsWithUnavailablePrice` — distinct items, not drop rows
Counted once per distinct `itemId` during the per-item aggregation loop, not once per `Drop` row
— verified in `getSummary_itemsWithUnavailablePrice_countsDistinctItemsNotDropRows` (3 rows, same
unpriceable item → `1`, not `3`).

### 4. `weeklyDrop.estimatedValueUsd` — current price only, never `acquisitionValueUsd`
`computeWeeklyDrop` multiplies `quantity × price.priceUsd()` from `PricingService`, and simply
never reads `Drop.getAcquisitionValueUsd()` at all for this calculation. Verified in
`getSummary_weeklyDrop_usesCurrentPriceNotAcquisitionValue_excludesUnavailable` (an
`acquisitionValueUsd` of `999.00` is deliberately planted and ignored; the result matches the
current price only). Drops with an unavailable current price are skipped entirely, not counted
as `0`.

### 5. `weeklyDrop.caseCount`/`skinOrGraffitiCount` — physical quantity, not row count
Same accumulation pattern as `totalItems`: `caseCount += qty` / `skinOrGraffitiCount += qty` per
matching drop in the window, never `+= 1`. Verified in
`getSummary_weeklyDrop_countsPhysicalQuantityNotDropRows` (one `CASE` drop with `quantity=5` →
`caseCount=5`).

### 6. Breakdown — no price-range filter added
`GET /portfolio/breakdown` implements exactly `search`, `type`, `page`, `size`, `sort` — nothing
else. The PRD §19 vs. `API_CONTRACT.md` §7 gap noted in the assessment is **not** addressed in
code, per PM's explicit instruction; it remains a documentation-only follow-up, not something
implemented or half-implemented here.

## Minor Decisions (PM Accepted Defaults)

- **CSV unavailable price:** both `Current Price` and `Current Value` cells render the literal
  string `Price unavailable` when `priceAvailable=false` — verified in
  `exportCsv_priceUnavailable_rendersPriceUnavailableLiteral`.
- **`highestValueItem` tie:** lowest `itemId` wins — verified in
  `getSummary_highestValueItem_picksMaxAggregatedValue_tieBreaksOnLowestItemId` (two items tied
  at `10.00`, itemId `3` beats itemId `5`).
- **`latestDrop` tie:** latest `acquisitionDate`, then latest `createdAt` — verified in
  `getSummary_latestDrop_picksLatestAcquisitionDate_tieBreaksOnLatestCreatedAt`.

---

# 4. Endpoint Details

## `GET /portfolio/summary`
No query params. Empty portfolio (zero non-deleted drops) → `totalValueUsd=0, totalItems=0,
highestValueItem=null, latestDrop=null, itemsWithUnavailablePrice=0, weeklyDrop={0,0,0}` —
verified in `getSummary_emptyPortfolio_returnsZeroedResponse`.

## `GET /portfolio/breakdown`
Aggregated per distinct `itemId` (`totalQuantity` = sum of `quantity` across that item's drop
rows). `search` matches item name (case-insensitive substring), `type` matches the `ItemType`
enum exactly. Sort whitelist `totalValueUsd`, `quantity` (maps to the `totalQuantity` field —
same naming mismatch already flagged in the assessment, not something I could silently rename
since it's the contract's own wording), `name`; default `name,asc` when `sort` is absent (not a
locked default, same "default field ascending" convention as `drop/`/`item/`). `page`/`size`
follow the same `page>=1`, `size 1..100` rule as every other paginated list in this codebase.

**Technical note (not a locked-contract detail, purely implementation):** since two of the three
sortable fields depend on a live price lookup and a `GROUP BY`, this endpoint fetches the user's
full filtered holdings, aggregates/prices/sorts/paginates all in Java — the same MVP-scale
approach already established for `drop/`'s `sort=currentValueUsd` in Milestone 5, applied here
for the same underlying reason (avoiding a SQL-level "latest price per item" join that isn't
needed at this scale).

## `GET /portfolio/export`
`200` + `Content-Type: text/csv` + `Content-Disposition: attachment; filename="dropfolio-export.csv"`
when the user has holdings; `204 No Content` when they don't. One row per `drops` entry (not
aggregated per item — the locked column list includes a per-row "Acquisition Price", which only
exists at that granularity; this was my stated working interpretation in the assessment, which
the PM's decision list didn't correct, so I proceeded with it). Sorted latest-first
(`acquisitionDate` desc, then `createdAt` desc — a sensible default, not a locked ordering).
Columns exactly per PRD.md §23: `Item,Type,Acquired Date,Quantity,Acquisition Price,Current
Price,Current Value`. A genuinely-absent `acquisitionValueUsd` (never entered at creation, distinct
from "pricing subsystem has no current price") renders as an empty cell, not `Price unavailable`
— that phrase is specifically about the pricing subsystem, not about optional user input.
Item names containing commas/quotes are properly CSV-quoted (verified in
`exportCsv_itemNameWithComma_isQuoted`).

---

# 5. Dependency Confirmation

`portfolio/` depends on `drop/` (read-only, `findAllByUserIdAndDeletedAtIsNull`), `item/`
(read-only, `findAllById`, same batching pattern as `drop/`), and `pricing/` (read-only,
`PricingService.getPrice(itemId)`, one call per **distinct** itemId across an entire
summary/breakdown/export computation — not per drop row). This confirms and extends the
necessary-but-undocumented `drop→pricing` dependency already noted in Milestone 5's report;
`SYSTEM_ARCHITECTURE.md`'s dependency sentence ("portfolio bergantung pada item + pricing")
still doesn't mention `drop/` at all, a pre-existing gap flagged again here rather than silently
worked around.

---

# 6. `Clock` Injection (New, Not in the Original Assessment)

While writing the weekly-window tests, I found that testing the "Wednesday 01:00 UTC" rule
deterministically requires controlling "now" — `Instant.now()` called directly inside the
service can't be tested reliably against a fixed calendar scenario (a test asserting "these
specific 2026-09 dates fall inside/outside the window" would silently become wrong once real
time moves past that window, or could randomly fail depending on which day the suite happens to
run). I added a single `Clock` bean (`common/config/ClockConfig.java`, `Clock.systemUTC()` in
production) and changed `PortfolioService`'s constructor to take a `Clock` instead of calling
`Instant.now()` directly, letting tests inject `Clock.fixed(...)`.

This is a small, additive testability change — no behavior change in production (still real UTC
"now", exactly as the PM decision requires), no new file outside `portfolio/`'s own concern
except the one shared `Clock` bean. Flagging it here since it wasn't anticipated in the original
assessment and technically touches a shared/common package, even though its only current
consumer is `portfolio/`.

---

# 7. Tests Added

## `portfolio/service/PortfolioServiceTest.java` (22 tests)
Empty-portfolio zeroing, `totalItems` summation, `itemsWithUnavailablePrice` distinct-item
counting, `totalValueUsd` exclusion of unavailable-price items, `highestValueItem`
tie-breaking, `latestDrop` tie-breaking, the weekly-window boundary (including the UTC-hour edge
case), `weeklyDrop` quantity-based counting, `weeklyDrop` current-price-only calculation,
breakdown pagination/sort validation, aggregation across multiple drops for the same item,
breakdown price-unavailable handling, search/type filtering, sort-by-`totalValueUsd`, CSV
empty/populated/unavailable-price/comma-escaping cases.

## `portfolio/controller/PortfolioControllerTest.java` (11 tests)
Every endpoint: happy path, `401` with no token. `summary`: empty-portfolio null fields render
correctly. `breakdown`: `422` for invalid sort, pagination `meta`, `null` `totalValueUsd`
round-trips correctly in JSON. `export`: `200` + correct headers, `204` for no data.

**As with every prior milestone's report from this environment: these tests have not actually
been executed here.** No Maven Central network access and no local `.m2` cache means the build
can't be compiled in this sandbox. Please run:

```powershell
mvn clean test
```

Expected: baseline **107 existing tests still PASS**, plus **22 + 11 = 33 new tests**, for
**140/140 PASS, BUILD SUCCESS**. Please send back the output if anything fails.

---

# 8. Manual Verification (Not Performed — Same Reason as Every Prior Report)

No running Azure SQL Edge/Redis instance in this sandbox. Recommended pass once `mvn clean test`
is green:

- `GET /portfolio/summary` with a mix of priced/unpriced holdings across several items,
  including at least one drop dated inside vs. just-outside the current UTC week boundary
  (e.g., one dated the Tuesday before this Wednesday 01:00 UTC, one dated exactly this
  Wednesday) → confirm `weeklyDrop` counts only the second.
- `GET /portfolio/breakdown` with `search`/`type`/`sort` combinations, confirm aggregation
  matches manually-computed totals.
- `GET /portfolio/export` → open the CSV in a spreadsheet app, confirm columns/values/quoting
  render correctly, especially for any item name containing a comma.
- `GET /portfolio/export` for a brand-new user with zero drops → confirm `204`, not `200` with
  an empty body.

---

# 9. Known Limitations / Interpretation Flags

1. **Breakdown/export are fully in-memory aggregations** (§4) — same MVP-scale trade-off
   already established and accepted for `drop/` in Milestone 5. Revisit if per-user holdings
   ever grow large enough for this to matter.
2. **Export CSV row granularity (per-drop, not per-item)** — my stated working interpretation
   from the assessment, not explicitly re-confirmed by name in the PM's decision list (which
   addressed the 6 numbered discrepancies plus the 2 minor defaults, not this specific point).
   Proceeding on it since it wasn't corrected, but noting again for visibility.
3. **`sort=quantity` maps to the `totalQuantity` response field** — the naming mismatch between
   the locked sort whitelist and the locked response field name, noted in the assessment,
   unresolved in the contract itself (out of my scope to rename — that's a documentation
   decision).
4. **`Clock` bean addition** (§6) — new but minimal, flagged for visibility since it wasn't in
   the original assessment.
5. **Build/tests not executed in this environment** — the same standing caveat as every prior
   milestone report from this sandbox.

---

# 10. Scope-Creep Confirmation

Not implemented, per the locked scope:

- Steam integration, inventory sync — none exists in this product
- New `portfolio` database table — none created (none needed)
- New Redis key — none added (reuses `pricing/`'s existing cache-aside exactly as-is)
- Price history endpoint — not touched
- `alert/`/`notification/` — not implemented
- PDF export — not implemented
- IDR conversion — not implemented
- Async export job — export remains fully synchronous
- Any endpoint or filter (including price-range on breakdown) beyond the 3 locked endpoints and
  their exact locked query params

`item/`, `pricing/`, and `drop/` (beyond the one additive repository method) were used strictly
read-only.

---

# 11. Programmer Handoff

1. **Run `mvn clean test` locally first** — the one step this environment can't do. Report back
   any failures.
2. Run the application and perform manual Postman verification per §8.
3. If `mvn clean test` passes at 140/140, this milestone can go through the same PM acceptance
   flow as Milestones 3–5.
4. Do not start Milestone 7 (or any other module) until PM explicitly authorizes it.
