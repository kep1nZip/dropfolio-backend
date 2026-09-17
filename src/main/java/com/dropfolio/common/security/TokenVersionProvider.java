package com.dropfolio.common.security;

/**
 * Abstraction so {@code common/security} can validate the {@code tokenVersion} claim
 * (SYSTEM_ARCHITECTURE.md §3.3 — instant JWT revocation) without depending on the
 * {@code user/} module — per CLAUDE_CONTEXT.md §3 dependency rule: "common tidak
 * bergantung modul manapun". The {@code user/} module provides the concrete
 * implementation (backed by {@code UserRepository}) and registers it as a bean.
 */
public interface TokenVersionProvider {

    /**
     * @return the current persisted {@code token_version} for the given user id,
     *         or empty if the user no longer exists / is not eligible to authenticate
     *         (e.g. soft-deleted).
     */
    java.util.Optional<Integer> currentTokenVersion(Long userId);
}
