-- V4__init_item_prices.sql
-- Scope: only the `item_prices` table (ERD.md §2.6) — the one table Milestone 4 (pricing/)
-- needs. V4 is the next migration after V3 (items) — chain V1 -> V2 -> V3 -> V4 stays intact.
--
-- fetched_at/created_at use DATETIMEOFFSET(6) directly (NOT DATETIME2), same Instant/Hibernate 6
-- fix already applied to items in V3/users in V2.
--
-- No unique constraint on (item_id, provider, fetched_at) — deliberately omitted per ERD.md §2.6:
-- duplicate identical timestamps are theoretically possible but harmless, since the latest row
-- is always selected via TOP 1 ... ORDER BY fetched_at DESC.

CREATE TABLE item_prices (
    id               BIGINT IDENTITY(1,1)    NOT NULL,
    item_id          BIGINT                  NOT NULL,
    provider         NVARCHAR(30)            NOT NULL,
    price_usd        DECIMAL(18,4)           NULL,
    price_available  BIT                     NOT NULL,
    fetched_at       DATETIMEOFFSET(6)       NOT NULL,
    created_at       DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_item_prices PRIMARY KEY (id),
    -- ERD.md §2.9 referential-actions table: items <-> item_prices = CASCADE.
    CONSTRAINT FK_item_prices_item FOREIGN KEY (item_id) REFERENCES items(id) ON DELETE CASCADE
);
GO

-- ERD.md §2.6 explicitly names this index: "kritis untuk query 'harga terkini per item'".
CREATE INDEX IX_item_prices_item_fetched ON item_prices(item_id, fetched_at DESC);
GO
