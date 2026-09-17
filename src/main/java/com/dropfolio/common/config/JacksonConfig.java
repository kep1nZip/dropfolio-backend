package com.dropfolio.common.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * M8 Postman-testing gap fix: by default, Jackson accepts {@code 1}/{@code 0} and
 * {@code "true"}/{@code "false"} (string) for a JSON boolean field (e.g.
 * {@code notifyEmail}/{@code notifyInApp}, notification preferences), silently coercing them —
 * only a genuinely unrecognized string like {@code "yes"} was rejected. This narrows Jackson's
 * global coercion config for booleans specifically (not touching numeric/other coercions used
 * elsewhere) so only a real JSON {@code true}/{@code false} literal is accepted; anything else
 * fails deserialization, which {@code GlobalExceptionHandler} already maps to a clean 422 via
 * {@code HttpMessageNotReadableException} — no new exception handler needed.
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer strictBooleanCoercionCustomizer() {
        return builder -> builder.postConfigurer(objectMapper ->
                objectMapper.coercionConfigFor(LogicalType.Boolean)
                        .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                        .setCoercion(CoercionInputShape.String, CoercionAction.Fail));
    }
}
