package com.dropfolio.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * M10 — SYSTEM_ARCHITECTURE.md §10: "CorsFilter (whitelist origin Next.js domain saja, tidak
 * wildcard di production)". {@code allowedOrigin} is a single, exact origin (scheme + host +
 * port, e.g. {@code https://app.dropfolio.example}) sourced from configuration/environment —
 * never a wildcard, and never hardcoded in Java, so each environment (local dev, staging,
 * production) points at its own real frontend origin without a code change.
 */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(String allowedOrigin) {
}
