package com.dropfolio.common.cache;

/**
 * Central builder for every Redis key pattern used across modules — CLAUDE_CONTEXT.md §8.1.
 * Keeping them in one place avoids typo drift between the module that writes a key and the
 * module that reads it (e.g. pricing/ writes price:item:{id}, portfolio/ reads it).
 */
public final class CacheKeys {

    private CacheKeys() {
    }

    public static String priceItem(Long itemId) {
        return "price:item:" + itemId;
    }

    public static String refreshToken(Long userId, String tokenFamilyId) {
        return "auth:refresh:" + userId + ":" + tokenFamilyId;
    }

    /** Pattern (no trailing wildcard) for revoking every refresh session of a user at once. */
    public static String refreshTokenUserPattern(Long userId) {
        return "auth:refresh:" + userId + ":*";
    }

    public static String steamLinkState(String jti) {
        return "steamlink:state:" + jti;
    }

    public static String syncLock(Long userId) {
        return "lock:sync:" + userId;
    }

    public static String schedulerLock(String jobName) {
        return "lock:scheduler:" + jobName;
    }

    public static String rateLimit(String bucket, String identifier) {
        return "ratelimit:" + bucket + ":" + identifier;
    }
}
