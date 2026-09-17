package com.dropfolio.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Milestone 6 addition — a single {@link Clock} bean so time-sensitive business logic (currently
 * only {@code PortfolioService}'s fixed-weekly-boundary calculation, PM Decision: always UTC,
 * never server-local time) can be exercised deterministically in tests via
 * {@code Clock.fixed(...)}, instead of depending on the real wall clock at test-run time.
 * Production code gets {@link Clock#systemUTC()}, matching the "always UTC" rule as-is.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
