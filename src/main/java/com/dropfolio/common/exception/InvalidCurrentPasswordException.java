package com.dropfolio.common.exception;

public class InvalidCurrentPasswordException extends UnauthorizedException {
    public InvalidCurrentPasswordException(String message) {
        super("INVALID_CURRENT_PASSWORD", message);
    }
}
