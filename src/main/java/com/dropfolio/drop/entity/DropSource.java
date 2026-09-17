package com.dropfolio.drop.entity;

/**
 * Mirrors the CHECK constraint on {@code drops.source} — ERD.md §2.7: {@code NOT NULL, CHECK
 * IN ('MANUAL')}. Only one value exists because Dropfolio holdings are 100% manual — there is
 * no Steam inventory sync in this product (CLAUDE_CONTEXT.md §15/§16, PM Decision).
 *
 * Modeled as an enum (rather than a raw String constant) purely for consistency with
 * {@code item/entity/ItemType} and to get automatic 422 handling for an invalid query-param
 * value via the existing {@code MethodArgumentTypeMismatchException} handler in
 * {@code GlobalExceptionHandler} — not a contract requirement, an implementation choice.
 */
public enum DropSource {
    MANUAL
}
