-- V11__init_email_jobs.sql
-- Scope: only `email_jobs` (ERD.md §2.12) — M8 Implementation Authorization §6/§11.
-- last_attempt_at/sent_at/created_at use DATETIMEOFFSET(6), same established fix since V2.

CREATE TABLE email_jobs (
    id                  BIGINT IDENTITY(1,1)   NOT NULL,
    notification_id     BIGINT                  NULL,
    recipient_email     NVARCHAR(320)           NOT NULL,
    subject             NVARCHAR(300)           NOT NULL,
    body                NVARCHAR(MAX)           NOT NULL,
    status              NVARCHAR(20)            NOT NULL DEFAULT 'PENDING',
    attempt_count       INT                     NOT NULL DEFAULT 0,
    max_attempts        INT                     NOT NULL DEFAULT 3,
    error_message       NVARCHAR(1000)          NULL,
    last_attempt_at     DATETIMEOFFSET(6)       NULL,
    sent_at             DATETIMEOFFSET(6)       NULL,
    created_at          DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_email_jobs PRIMARY KEY (id),
    -- ERD.md §3: notifications<->email_jobs = SET NULL ("email transaksional lain ... tidak
    -- selalu punya notification terkait" — nullable by design, not just for this milestone).
    CONSTRAINT FK_email_jobs_notification FOREIGN KEY (notification_id) REFERENCES notifications(id) ON DELETE SET NULL,
    CONSTRAINT CK_email_jobs_status CHECK (status IN ('PENDING', 'SENT', 'FAILED'))
);
GO

-- ERD.md §2.12: "kritis untuk worker polling WHERE status = 'PENDING' ORDER BY created_at" —
-- exactly EmailWorker's query.
CREATE INDEX IX_email_jobs_status_created ON email_jobs(status, created_at);
GO
