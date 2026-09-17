package com.dropfolio.common.audit;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * AOP aspect around @Auditable methods.
 *
 * MILESTONE-1 SCOPE NOTE: skeleton only. Actual persistence into audit_logs (entity +
 * repository) is added once the entity exists (that entity has no natural module home of
 * its own besides being a cross-cutting sink written to by admin/ actions — table itself is
 * created via JPA/DDL when the admin/ module lands per CLAUDE_CONTEXT.md §12 order item 10).
 * Wiring AuditLogRepository here now would mean referencing an entity that doesn't exist yet.
 */
@Aspect
@Component
public class AuditAspect {

    @Around("@annotation(auditable)")
    public Object audit(ProceedingJoinPoint joinPoint, Auditable auditable) throws Throwable {
        // TODO(milestone: admin/): persist AuditLog(action=auditable.action(), actorUserId=...,
        // targetType=..., targetId=..., metadata=...) AFTER successful execution only —
        // append-only, never on exception path per CLAUDE_CONTEXT.md security rules.
        return joinPoint.proceed();
    }
}
