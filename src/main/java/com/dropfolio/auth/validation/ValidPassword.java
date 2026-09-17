package com.dropfolio.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Password policy — API_CONTRACT.md §0.13: min 8 char, at least 1 letter and 1 digit.
 * {@code @Size(min=8)} on the field handles the length part; this annotation handles the
 * letter+digit composition rule.
 */
@Documented
@Constraint(validatedBy = ValidPasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    String message() default "must contain at least 1 letter and 1 digit";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
