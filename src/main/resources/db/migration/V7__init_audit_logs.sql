-- V7__init_audit_logs.sql
-- Scope: only the `audit_logs` table (ERD.md §2.13) — minimal persistence required by
-- M7 Implementation Authorization §3 for `ADMIN_TRIGGER_SYNC`. No admin audit viewer,
-- no GET /admin/audit-logs, no retroactive activation of other @Auditable usages — this
-- migration only creates the table; which actions actually write to it in M7 is an
-- application-layer decision (see admin/service/AdminSyncService), not a schema concern.
--
-- created_at uses DATETIMEOFFSET(6) directly (NOT the DATETIME2 literally written in
-- ERD.md §2.13) — same Instant/Hibernate 6 fix already applied to every previous
-- Instant-mapped table (see V2-V6). No `updated_at` column, intentionally — ERD.md §2.13:
-- "tabel ini append-only. Tidak ada UPDATE atau DELETE yang diizinkan lewat application layer".

CREATE TABLE audit_logs (
    id               BIGINT IDENTITY(1,1)   NOT NULL,
    actor_user_id    BIGINT                  NULL,
    action           NVARCHAR(100)           NOT NULL,
    entity_type      NVARCHAR(50)            NULL,
    entity_id        BIGINT                  NULL,
    metadata         NVARCHAR(MAX)           NULL,
    ip_address       NVARCHAR(45)            NULL,
    created_at       DATETIMEOFFSET(6)       NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_audit_logs PRIMARY KEY (id),
    -- ERD.md §3 Relationship Summary: users <-> audit_logs = SET NULL. entity_id deliberately
    -- has NO FK (ERD.md §2.13: target table varies and the target row may already be gone —
    -- the audit log must stay intact regardless).
    CONSTRAINT FK_audit_logs_actor FOREIGN KEY (actor_user_id) REFERENCES users(id) ON DELETE SET NULL
);
GO

CREATE INDEX IX_audit_logs_actor_created ON audit_logs(actor_user_id, created_at DESC);
GO

CREATE INDEX IX_audit_logs_action_created ON audit_logs(action, created_at DESC);
GO
