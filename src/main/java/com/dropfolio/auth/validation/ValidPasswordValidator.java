package com.dropfolio.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.util.regex.Pattern;

/** Requires at least 1 letter (any case) and 1 digit — length is validated separately via @Size. */
public class ValidPasswordValidator implements ConstraintValidator<ValidPassword, String> {

    private static final Pattern LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern DIGIT = Pattern.compile("[0-9]");

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            // @NotBlank handles the "missing" case separately with its own message.
            return true;
        }
        return LETTER.matcher(value).find() && DIGIT.matcher(value).find();
    }
}
