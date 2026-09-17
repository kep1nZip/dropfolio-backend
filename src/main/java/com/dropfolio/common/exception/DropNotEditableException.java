package com.dropfolio.common.exception;

public class DropNotEditableException extends ConflictException {
    public DropNotEditableException(String message) {
        super("DROP_NOT_EDITABLE", message);
    }
}
