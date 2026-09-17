package com.dropfolio.common.exception;

public class AccountDeactivatedException extends UnauthorizedException {
    public AccountDeactivatedException(String message) {
        super("ACCOUNT_DEACTIVATED", message);
    }
}
