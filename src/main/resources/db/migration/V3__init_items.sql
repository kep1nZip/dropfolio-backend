-- V3__init_items.sql
-- Scope: only the `items` table (ERD.md §2.5) — the one table Milestone 3 (item/) needs.
-- Numbered V3 because V1 (users/roles/user_roles) and V2 (Instant/DATETIMEOFFSET fix) already
-- exist in this project. Adjust the version number if your actual next-available Flyway
-- version differs from V3 by the time this is applied.
--
-- created_at/updated_at use DATETIMEOFFSET(6) directly (NOT DATETIME2) — applying the same
-- fix V2 already made for users/user_roles from the start, since Hibernate 6.5 maps
-- java.time.Instant to DATETIMEOFFSET, not DATETIME2. No need to repeat that migration dance
-- for this table.

CREATE TABLE items (
    id                BIGINT IDENTITY(1,1)    NOT NULL,
    name              NVARCHAR(200)           NOT NULL,
    type              NVARCHAR(20)            NOT NULL,
    market_hash_name  NVARCHAR(300)           NOT NULL,
    icon_url          NVARCHAR(500)           NULL,
    is_active         BIT                     NOT NULL DEFAULT 1,
    created_at        DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at        DATETIMEOFFSET(6)       NULL,
    CONSTRAINT PK_items PRIMARY KEY (id),
    CONSTRAINT UQ_items_market_hash_name UNIQUE (market_hash_name),
    CONSTRAINT CK_items_type CHECK (type IN ('CASE', 'SKIN', 'GRAFFITI'))
);
GO

-- ERD.md §2.5 explicitly names this index for catalog filter/search.
CREATE INDEX IX_items_type_active ON items(type, is_active);
GO
