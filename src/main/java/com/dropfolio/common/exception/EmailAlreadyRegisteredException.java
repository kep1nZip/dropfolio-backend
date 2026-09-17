package com.dropfolio.common.exception;

public class EmailAlreadyRegisteredException extends ConflictException {
    public EmailAlreadyRegisteredException(String message) {
        super("EMAIL_ALREADY_REGISTERED", message);
    }
}
