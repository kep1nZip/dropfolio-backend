package com.dropfolio.pricing.service;

import com.dropfolio.common.exception.ValidationException;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;

/** Supported {@code range} values of the price-history endpoint. Boundaries are computed in UTC. */
public enum PriceHistoryRange {
    D7("7d", Duration.ofDays(7)),
    D30("30d", Duration.ofDays(30)),
    D90("90d", Duration.ofDays(90)),
    Y1("1y", Duration.ofDays(365)),
    ALL("all", null);

    private final String param;
    private final Duration window;

    PriceHistoryRange(String param, Duration window) {
        this.param = param;
        this.window = window;
    }

    public String param() {
        return param;
    }

    /** {@code true} for {@link #ALL}: no lower bound. */
    public boolean unbounded() {
        return window == null;
    }

    /** Lower bound (inclusive) relative to {@code now}; only valid when not {@link #unbounded()}. */
    public Instant lowerBound(Instant now) {
        return now.minus(window);
    }

    public static PriceHistoryRange fromParam(String value) {
        if (value != null) {
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (PriceHistoryRange range : values()) {
                if (range.param.equals(normalized)) {
                    return range;
                }
            }
        }
        throw new ValidationException("range must be one of: 7d, 30d, 90d, 1y, all");
    }
}
