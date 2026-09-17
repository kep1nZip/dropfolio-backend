-- V12__fix_notifications_alert_fk.sql
-- V10 (already applied/checksummed against real databases) created `FK_notifications_alert`
-- with `ON DELETE SET NULL`, which SQL Server rejects at migration time on a fresh database
-- with Error 1785 ("Introducing FOREIGN KEY constraint ... may cause cycles or multiple
-- cascade paths") — it conflicts with the `users -> notifications` CASCADE FK (two distinct
-- delete paths reaching `notifications`). Because V10 was already applied elsewhere, the fix
-- is this new migration, not an edit to V10 (editing an applied migration causes a Flyway
-- checksum mismatch on any environment that already ran it).
--
-- `alert_id` itself is unchanged — still nullable, still no data migration needed here (SET
-- NULL never had a chance to fire differently from NO ACTION in practice, since this table is
-- new in M8 and no environment old enough to have triggered the difference exists yet).
--
-- Application-layer consequence: the DB no longer auto-nulls `notifications.alert_id` when a
-- `price_alerts` row is hard-deleted. `AlertService.delete()` handles this explicitly via
-- `NotificationRepository.detachFromAlert(...)` before the alert delete, in the same
-- transaction — replicating the original SET NULL intent at the application layer.

ALTER TABLE notifications
DROP CONSTRAINT FK_notifications_alert;
GO

ALTER TABLE notifications
ADD CONSTRAINT FK_notifications_alert
    FOREIGN KEY (alert_id)
    REFERENCES price_alerts(id)
    ON DELETE NO ACTION;
GO
