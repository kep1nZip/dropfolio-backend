-- V9__init_notification_preferences.sql
-- Scope: only `notification_preferences` (ERD.md §2.10) — M8 Implementation Authorization §6/§14.
--
-- created_at/updated_at use DATETIMEOFFSET(6) directly, same established fix since V2.
--
-- ERD.md §2.10: this row is meant to be created automatically at registration — "bukan
-- lazy-created saat user pertama kali buka settings, supaya query preferensi tidak perlu
-- null-check di service layer." AuthService.register() (M8) now does this for every NEW user.
-- But this migration runs after users that registered under M1-M7 already exist, and none of
-- them have a row here yet — without a backfill, GET/PATCH /notifications/preferences and
-- AlertEvaluationService's preference checks would hit a genuinely missing row for every
-- pre-M8 user, which is exactly the null-check-in-service-layer problem this table's own
-- design note says to avoid. The INSERT...SELECT below closes that gap in the same migration
-- that creates the table, so "the row always exists" is true immediately, not just for users
-- who register after this point.

CREATE TABLE notification_preferences (
    id                  BIGINT IDENTITY(1,1)   NOT NULL,
    user_id             BIGINT                  NOT NULL,
    email_enabled       BIT                     NOT NULL DEFAULT 1,
    in_app_enabled      BIT                     NOT NULL DEFAULT 1,
    created_at          DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at          DATETIMEOFFSET(6)       NULL,
    CONSTRAINT PK_notification_preferences PRIMARY KEY (id),
    CONSTRAINT UQ_notification_preferences_user UNIQUE (user_id),
    CONSTRAINT FK_notification_preferences_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);
GO

-- Backfill: one default-preferences row for every user that existed before this migration ran.
INSERT INTO notification_preferences (user_id, email_enabled, in_app_enabled, created_at)
SELECT id, 1, 1, SYSUTCDATETIME()
FROM users
WHERE id NOT IN (SELECT user_id FROM notification_preferences);
GO
