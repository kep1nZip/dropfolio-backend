package com.dropfolio.common.security;

import com.dropfolio.common.envelope.ApiError;
import com.dropfolio.common.envelope.ApiErrorResponse;
import com.dropfolio.common.envelope.ErrorCategory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;

/** Ensures 401 responses use the standard envelope, not Spring Security's default body. */
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                          AuthenticationException authException) throws IOException {
        response.setStatus(ErrorCategory.UNAUTHORIZED.httpStatus().value());
        response.setContentType("application/json");
        var body = ApiErrorResponse.of(ApiError.of(ErrorCategory.UNAUTHORIZED, "Authentication required"));
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
