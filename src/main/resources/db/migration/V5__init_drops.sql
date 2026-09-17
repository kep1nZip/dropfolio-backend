-- V5__init_drops.sql
-- Scope: only the `drops` table (ERD.md §2.7) — 100% manual holdings, no `steam_asset_id`,
-- no dedup constraint (PM Decision: no dedup for manual entries, PRD.md §33).
--
-- FK to users/items are the default SQL Server referential action (NO ACTION) — ERD.md §3
-- Relationship Summary locks NO ACTION for both `users<->drops` and `items<->drops` (neither
-- users nor items are ever hard-deleted, so cascading was never a consideration here).
--
-- created_at/updated_at/deleted_at use DATETIMEOFFSET(6), NOT the DATETIME2 literally written
-- in ERD.md §2.7 for this table — same Instant/Hibernate 6 fix already applied to
-- users/user_roles (V2) and items/item_prices (V3/V4). ERD.md §2.7 wasn't updated to carry that
-- fix forward for `drops`; applying it here from the start rather than reintroducing a known,
-- already-fixed mapping bug (see drop/entity/Drop.java javadoc and
-- MILESTONE_5_COMPLETION_REPORT.md for the full note). acquisition_date is unaffected — it's a
-- plain DATE (java.time.LocalDate), not an Instant-mapped column (ERD.md §2.9: represents a
-- calendar date, not a precise timestamp).

CREATE TABLE drops (
    id                      BIGINT IDENTITY(1,1)   NOT NULL,
    user_id                 BIGINT                  NOT NULL,
    item_id                 BIGINT                  NOT NULL,
    source                  NVARCHAR(20)            NOT NULL,
    quantity                INT                     NOT NULL DEFAULT 1,
    acquisition_value_usd   DECIMAL(18,4)           NULL,
    acquisition_date        DATE                    NOT NULL,
    created_at              DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at              DATETIMEOFFSET(6)       NULL,
    deleted_at              DATETIMEOFFSET(6)       NULL,
    CONSTRAINT PK_drops PRIMARY KEY (id),
    CONSTRAINT FK_drops_user FOREIGN KEY (user_id) REFERENCES users(id),
    CONSTRAINT FK_drops_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT CK_drops_source CHECK (source IN ('MANUAL')),
    CONSTRAINT CK_drops_quantity CHECK (quantity > 0)
);
GO

-- ERD.md §2.7: "list holding/drop per user".
CREATE INDEX IX_drops_user_id ON drops(user_id);
GO

-- ERD.md §2.7: "portfolio valuation aggregate".
CREATE INDEX IX_drops_item_id ON drops(item_id);
GO
