package com.dropfolio.auth.dto;

import java.util.List;

/** TECHNICAL_SPEC.md §3.1 — nested inside LoginResponse. */
public record UserSummary(Long id, String displayName, List<String> roles) {
}
