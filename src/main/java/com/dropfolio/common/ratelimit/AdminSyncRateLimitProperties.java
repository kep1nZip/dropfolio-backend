package com.dropfolio.common.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code ratelimit.admin-sync.*} — API_CONTRACT.md §11: 1 req / 60s / admin for
 * {@code POST /admin/sync-jobs/price-sync}.
 *
 * Deliberately a SEPARATE {@code @ConfigurationProperties} record from
 * {@link RateLimitProperties} rather than a new component added to it — several existing
 * M1-M6 tests construct {@code new RateLimitProperties(...)} directly via its canonical
 * constructor (record), so adding a component there would change its arity and break every
 * one of those call sites for a rate limit that isn't even IP-based like the other two
 * (login/register). Scoped per-admin (identifier = authenticated admin user id), enforced in
 * {@code admin/service/AdminSyncService} rather than a {@code RateLimitFilter} instance,
 * because identity isn't available yet at the point {@code RateLimitFilter} runs in the chain
 * (it is registered before {@code JwtAuthenticationFilter} — see {@code SecurityConfig}).
 */
@ConfigurationProperties(prefix = "ratelimit.admin-sync")
public record AdminSyncRateLimitProperties(int windowSeconds, int maxRequests) {
}
