-- V6__init_sync_jobs.sql
-- Scope: only the `sync_jobs` table (ERD.md §2.11) — the table Milestone 7 (scheduler/ +
-- admin/) needs. V6 is the next migration after V5 (drops) — chain V1..V5 stays intact,
-- nothing in V1-V5 is modified (M7 Implementation Authorization §18).
--
-- started_at/finished_at use DATETIMEOFFSET(6) directly (NOT the DATETIME2 literally written
-- in ERD.md §2.11) — same Instant/Hibernate 6 fix already applied to every previous
-- Instant-mapped table (users/user_roles V2, items V3, item_prices V4, drops V5). Applied here
-- from the start rather than reintroducing the same already-fixed mapping bug.
--
-- No `QUEUED` status value — API_CONTRACT.md §11 locks the enum at RUNNING | SUCCESS | FAILED
-- only; `status = RUNNING` is set the moment the row is created (accepted), not when the
-- executor thread actually starts processing items.

CREATE TABLE sync_jobs (
    id                     BIGINT IDENTITY(1,1)   NOT NULL,
    job_type               NVARCHAR(30)            NOT NULL,
    status                 NVARCHAR(20)            NOT NULL,
    items_processed        INT                     NOT NULL DEFAULT 0,
    error_message          NVARCHAR(1000)          NULL,
    triggered_by           NVARCHAR(20)            NOT NULL,
    triggered_by_user_id   BIGINT                  NULL,
    started_at             DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    finished_at            DATETIMEOFFSET(6)       NULL,
    CONSTRAINT PK_sync_jobs PRIMARY KEY (id),
    -- ERD.md §3 Relationship Summary: users <-> sync_jobs = SET NULL (a deleted/soft-deleted
    -- admin user must not block deletion, and the job log itself must remain intact).
    CONSTRAINT FK_sync_jobs_user FOREIGN KEY (triggered_by_user_id) REFERENCES users(id) ON DELETE SET NULL,
    CONSTRAINT CK_sync_jobs_job_type CHECK (job_type IN ('PRICE_SYNC', 'ALERT_EVALUATION')),
    CONSTRAINT CK_sync_jobs_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    CONSTRAINT CK_sync_jobs_triggered_by CHECK (triggered_by IN ('SCHEDULER', 'ADMIN'))
);
GO

-- ERD.md §2.11 explicitly names this index: "admin dashboard 'last sync' & 'failed sync' queries".
CREATE INDEX IX_sync_jobs_type_started ON sync_jobs(job_type, started_at DESC);
GO
