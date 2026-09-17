DECLARE @constraint_name NVARCHAR(255);

SELECT @constraint_name = dc.name
FROM sys.default_constraints dc
JOIN sys.columns c
    ON dc.parent_object_id = c.object_id
    AND dc.parent_column_id = c.column_id
JOIN sys.tables t
    ON t.object_id = c.object_id
WHERE t.name = 'users'
  AND c.name = 'created_at';

IF @constraint_name IS NOT NULL
BEGIN
    EXEC('ALTER TABLE users DROP CONSTRAINT [' + @constraint_name + ']');
END;

ALTER TABLE users
ALTER COLUMN created_at DATETIMEOFFSET(6) NOT NULL;

ALTER TABLE users
ADD CONSTRAINT DF_users_created_at
    DEFAULT SYSUTCDATETIME() FOR created_at;


ALTER TABLE users
ALTER COLUMN updated_at DATETIMEOFFSET(6) NULL;

ALTER TABLE users
ALTER COLUMN deleted_at DATETIMEOFFSET(6) NULL;


DECLARE @assigned_constraint_name NVARCHAR(255);

SELECT @assigned_constraint_name = dc.name
FROM sys.default_constraints dc
JOIN sys.columns c
    ON dc.parent_object_id = c.object_id
    AND dc.parent_column_id = c.column_id
JOIN sys.tables t
    ON t.object_id = c.object_id
WHERE t.name = 'user_roles'
  AND c.name = 'assigned_at';

IF @assigned_constraint_name IS NOT NULL
BEGIN
    EXEC('ALTER TABLE user_roles DROP CONSTRAINT [' + @assigned_constraint_name + ']');
END;

ALTER TABLE user_roles
ALTER COLUMN assigned_at DATETIMEOFFSET(6) NOT NULL;

ALTER TABLE user_roles
ADD CONSTRAINT DF_user_roles_assigned_at
    DEFAULT SYSUTCDATETIME() FOR assigned_at;