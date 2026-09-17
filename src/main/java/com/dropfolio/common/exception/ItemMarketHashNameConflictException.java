package com.dropfolio.common.exception;

/**
 * {@code POST /items} rejects a duplicate {@code marketHashName} with 409 — API_CONTRACT.md §4.
 * There is NO domain-specific {@code code} for this in the §0.6.2 registry (only auth/steam/
 * drop/sync conflicts have named codes), and §0.6.2's own fallback rule is explicit: any error
 * without a registry row MUST use {@code error.code = error.category}. Inventing a new code
 * like {@code ITEM_MARKET_HASH_NAME_ALREADY_EXISTS} would violate that rule directly — this
 * class exists only to carry a message, its {@code code} is deliberately fixed to
 * {@code "CONFLICT"} (same as its category), not a new registry entry.
 */
public class ItemMarketHashNameConflictException extends ConflictException {

    public ItemMarketHashNameConflictException(String message) {
        super("CONFLICT", message);
    }
}
