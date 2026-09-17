-- V1__init_users_roles.sql
-- Scope: only the tables needed through Milestone 2 (auth/ + user/ dependency).
-- Source of truth: ERD.md §2.1 (users), §2.2 (roles), §2.3 (user_roles).
-- Remaining 10 tables land in their own migration as each module is implemented
-- (CLAUDE_CONTEXT.md §12 sequencing) — NOT created ahead of time here, per scope freeze.

CREATE TABLE users (
    id              BIGINT IDENTITY(1,1)   NOT NULL,
    email           NVARCHAR(320)          NULL,
    password_hash   NVARCHAR(255)          NULL,
    display_name    NVARCHAR(100)          NOT NULL,
    token_version   INT                    NOT NULL DEFAULT 0,
    status          NVARCHAR(20)           NOT NULL DEFAULT 'ACTIVE',
    created_at      DATETIME2              NOT NULL DEFAULT SYSUTCDATETIME(),
    updated_at      DATETIME2              NULL,
    deleted_at      DATETIME2              NULL,
    CONSTRAINT PK_users PRIMARY KEY (id),
    CONSTRAINT CK_users_status CHECK (status IN ('ACTIVE', 'DEACTIVATED', 'DELETED'))
);
GO

-- Filtered unique index: only enforced while email is non-null (steam-only accounts may have
-- a null email; multiple NULLs must be allowed, which a plain UNIQUE column would forbid on
-- some engines but SQL Server's own UNIQUE constraint already allows multiple NULLs anyway —
-- the filtered index is kept explicit per ERD.md §2.1 to document the intent unambiguously).
CREATE UNIQUE INDEX UX_users_email ON users(email) WHERE email IS NOT NULL;
GO

CREATE INDEX IX_users_status ON users(status);
GO

CREATE TABLE roles (
    id      BIGINT IDENTITY(1,1)    NOT NULL,
    name    NVARCHAR(30)            NOT NULL,
    CONSTRAINT PK_roles PRIMARY KEY (id),
    CONSTRAINT UQ_roles_name UNIQUE (name),
    CONSTRAINT CK_roles_name CHECK (name IN ('USER', 'ADMIN', 'PREMIUM'))
);
GO

CREATE TABLE user_roles (
    user_id     BIGINT      NOT NULL,
    role_id     BIGINT      NOT NULL,
    assigned_at DATETIME2   NOT NULL DEFAULT SYSUTCDATETIME(),
    CONSTRAINT PK_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT FK_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT FK_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE NO ACTION
);
GO

-- Seed data — ERD.md §2.2: "Seed data minimum: USER, ADMIN" plus the explicit note that
-- PREMIUM is "prepared as a row" (disiapkan sebagai row) even though no MVP logic uses it —
-- seeded here too so the row exists ahead of any future PREMIUM feature, per that note.
INSERT INTO roles (name) VALUES ('USER');
INSERT INTO roles (name) VALUES ('ADMIN');
INSERT INTO roles (name) VALUES ('PREMIUM');
GO
