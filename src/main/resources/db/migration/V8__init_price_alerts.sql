-- V8__init_price_alerts.sql
-- Scope: only `price_alerts` (ERD.md §2.8) — M8 Implementation Authorization §6/§7.
-- V1-V7 untouched; this is the next migration in the chain.
--
-- created_at/triggered_at use DATETIMEOFFSET(6) directly (NOT the DATETIME2 literally written
-- in ERD.md §2.8) — same Instant/Hibernate 6 fix already applied to every previous
-- Instant-mapped table since V2.
--
-- Hard delete allowed on this table (ERD.md §6.4 / API_CONTRACT.md §9 DELETE /alerts/{id}) —
-- no soft-delete column, unlike `users`/`drops`.

CREATE TABLE price_alerts (
    id                  BIGINT IDENTITY(1,1)   NOT NULL,
    user_id             BIGINT                  NOT NULL,
    item_id             BIGINT                  NOT NULL,
    target_price_usd    DECIMAL(18,4)           NOT NULL,
    notify_email        BIT                     NOT NULL DEFAULT 1,
    notify_in_app       BIT                     NOT NULL DEFAULT 1,
    status              NVARCHAR(20)            NOT NULL DEFAULT 'ACTIVE',
    created_at          DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    triggered_at        DATETIMEOFFSET(6)       NULL,
    CONSTRAINT PK_price_alerts PRIMARY KEY (id),
    -- ERD.md §3 Relationship Summary: users<->price_alerts = CASCADE, items<->price_alerts = NO ACTION.
    CONSTRAINT FK_price_alerts_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT FK_price_alerts_item FOREIGN KEY (item_id) REFERENCES items(id),
    CONSTRAINT CK_price_alerts_target_price CHECK (target_price_usd > 0),
    CONSTRAINT CK_price_alerts_status CHECK (status IN ('ACTIVE', 'TRIGGERED', 'DISABLED'))
);
GO

-- ERD.md §2.8: "kritis untuk AlertEvaluationJob" — per-item lookup of ACTIVE alerts,
-- exactly the query AlertEvaluationService.evaluate(...) runs for every priced item.
CREATE INDEX IX_price_alerts_item_status ON price_alerts(item_id, status);
GO

CREATE INDEX IX_price_alerts_user_id ON price_alerts(user_id);
GO
