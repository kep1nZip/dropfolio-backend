package com.dropfolio.common.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method whose invocation must produce an append-only audit_logs row —
 * CLAUDE_CONTEXT.md §7 / TECHNICAL_SPEC.md §0 (resolved: only 4 admin actions are audited
 * in MVP, self-service actions like DELETE /users/me are explicitly NOT audited).
 *
 * `action` must be one of the locked enum values in ERD.md §2.13 / §4 (audit_logs.action).
 * Do not introduce new action values here — that is scope expansion per CLAUDE_CONTEXT.md §11.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Auditable {
    String action();
}
