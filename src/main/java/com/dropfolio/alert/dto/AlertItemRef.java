package com.dropfolio.alert.dto;

/** API_CONTRACT.md §9 — nested item ref inside an alert response, e.g. { "id": 5, "name": "Revolution Case" }. */
public record AlertItemRef(Long id, String name) {
}
