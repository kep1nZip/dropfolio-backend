package com.dropfolio.common.security;

import com.dropfolio.common.envelope.ApiError;
import com.dropfolio.common.envelope.ApiErrorResponse;
import com.dropfolio.common.envelope.ErrorCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;

/** Ensures 403 responses (insufficient role) use the standard envelope. */
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public RestAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                        AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(ErrorCategory.FORBIDDEN.httpStatus().value());
        response.setContentType("application/json");
        var body = ApiErrorResponse.of(ApiError.of(ErrorCategory.FORBIDDEN, "Insufficient role"));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
