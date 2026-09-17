-- V10__init_notifications.sql
-- Scope: only `notifications` (ERD.md §2.9) — M8 Implementation Authorization §6/§8.
-- created_at/read_at use DATETIMEOFFSET(6), same established fix since V2.
-- Hard delete allowed (ERD.md §6.4) — no soft-delete column.

CREATE TABLE notifications (
    id              BIGINT IDENTITY(1,1)   NOT NULL,
    user_id         BIGINT                  NOT NULL,
    alert_id        BIGINT                  NULL,
    type            NVARCHAR(30)            NOT NULL,
    title           NVARCHAR(200)           NOT NULL,
    message         NVARCHAR(1000)          NOT NULL,
    read_at         DATETIMEOFFSET(6)       NULL,
    created_at      DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_notifications PRIMARY KEY (id),
    -- ERD.md §3: users<->notifications = CASCADE, price_alerts<->notifications = SET NULL
    -- (alert_id nullable by design — ERD.md §2.9: "membuka ruang untuk notification non-alert
    -- ... di masa depan tanpa migrasi struktural"; M8 only ever populates it for PRICE_ALERT).
    CONSTRAINT FK_notifications_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE NO ACTION,
    CONSTRAINT FK_notifications_alert FOREIGN KEY (alert_id) REFERENCES price_alerts(id) ON DELETE SET NULL,
    CONSTRAINT CK_notifications_type CHECK (type IN ('PRICE_ALERT', 'SYSTEM'))
);
GO

-- ERD.md §2.9: "query 'unread count' & list notification center" — exactly GET /notifications'
-- meta.unreadCount and the unreadOnly filter.
CREATE INDEX IX_notifications_user_read ON notifications(user_id, read_at);
GO
