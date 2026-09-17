package com.dropfolio.scheduler.lock;

import com.dropfolio.common.cache.CacheKeys;
import com.dropfolio.scheduler.config.SchedulerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Distributed lock over {@code lock:scheduler:{jobName}} — CLAUDE_CONTEXT.md §8.1/§8.4, used
 * by both the scheduler tick and the admin manual trigger (same key, M7 Implementation
 * Authorization §13). Uses the existing Redis infrastructure only — no Redisson, no new
 * library (§13 explicit prohibition).
 *
 * Injects {@link StringRedisTemplate} (Spring Boot's own auto-configured bean, distinct from
 * the app's custom {@code RedisTemplate<String, Object>} in {@code common/cache/RedisConfig})
 * rather than the JSON-serializing template — the lock value is a raw owner token compared
 * byte-for-byte by the release Lua script, and {@code GenericJackson2JsonRedisSerializer} would
 * wrap a plain string in JSON quoting/type metadata, breaking that comparison.
 */
@Component
public class SchedulerLockService {

    private static final Logger log = LoggerFactory.getLogger(SchedulerLockService.class);

    /** Check-then-delete, atomic — never release a lock owned by another execution (§13). */
    private static final RedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redisTemplate;
    private final Duration lockTtl;

    public SchedulerLockService(StringRedisTemplate redisTemplate, SchedulerProperties schedulerProperties) {
        this.redisTemplate = redisTemplate;
        this.lockTtl = Duration.ofSeconds(schedulerProperties.lock().ttlSeconds());
    }

    /**
     * @return a random owner token if the lock was acquired, or {@code null} if another
     *         execution currently holds it. Callers MUST pass the returned token to
     *         {@link #release(String, String)} — never invent their own.
     */
    public String tryAcquire(String jobName) {
        String key = CacheKeys.schedulerLock(jobName);
        String ownerToken = UUID.randomUUID().toString();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(key, ownerToken, lockTtl);
        return Boolean.TRUE.equals(acquired) ? ownerToken : null;
    }

    /**
     * No-op if {@code ownerToken} is null or the key no longer matches it (already expired, or
     * — should never happen — owned by a different execution). TTL is the safety net if this
     * call itself fails (e.g. Redis briefly unreachable): the lock still expires on its own.
     */
    public void release(String jobName, String ownerToken) {
        if (ownerToken == null) {
            return;
        }
        String key = CacheKeys.schedulerLock(jobName);
        try {
            redisTemplate.execute(RELEASE_SCRIPT, List.of(key), ownerToken);
        } catch (Exception e) {
            log.warn("Failed to release scheduler lock {} (will expire via TTL in the worst case)", key, e);
        }
    }
}
