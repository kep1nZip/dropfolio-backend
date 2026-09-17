package com.dropfolio.common.exception;

public class DropNotDeletableException extends ConflictException {
    public DropNotDeletableException(String message) {
        super("DROP_NOT_DELETABLE", message);
    }
}
