package com.dropfolio.common.envelope;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * Standard success envelope — CLAUDE_CONTEXT.md §7.1 / API_CONTRACT.md §0.5.
 *
 * <pre>
 * { "success": true, "data": {}, "meta": {} }
 * </pre>
 *
 * {@code meta} is optional (e.g. pagination info, {@code unreadCount}) and omitted from the
 * JSON body when null — the contract shows {@code meta} present only where relevant.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(boolean success, T data, Map<String, Object> meta) {

    public static <T> ApiResponse<T> of(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static <T> ApiResponse<T> of(T data, Map<String, Object> meta) {
        return new ApiResponse<>(true, data, meta);
    }
}
