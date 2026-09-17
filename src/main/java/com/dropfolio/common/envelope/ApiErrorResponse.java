package com.dropfolio.common.envelope;

/**
 * Standard error envelope — CLAUDE_CONTEXT.md §7.1 / API_CONTRACT.md §0.6.
 *
 * <pre>
 * { "success": false, "error": { "category": "CONFLICT", "code": "DROP_NOT_EDITABLE", "message": "..." } }
 * </pre>
 */
public record ApiErrorResponse(boolean success, ApiError error) {

    public static ApiErrorResponse of(ApiError error) {
        return new ApiErrorResponse(false, error);
    }
}
