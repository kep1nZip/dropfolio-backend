package com.dropfolio.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds jwt.* from application.yml — secrets always via env var, never hardcoded. */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(String secret, long accessTokenTtlSeconds, long refreshTokenTtlSeconds) {
}
