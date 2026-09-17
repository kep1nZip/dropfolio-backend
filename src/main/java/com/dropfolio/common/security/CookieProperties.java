package com.dropfolio.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Binds app.cookie.* — operational toggle only, see application.yml comment. */
@ConfigurationProperties(prefix = "app.cookie")
public record CookieProperties(boolean secure) {
}
