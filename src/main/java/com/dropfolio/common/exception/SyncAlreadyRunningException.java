package com.dropfolio.common.exception;

public class SyncAlreadyRunningException extends ConflictException {
    public SyncAlreadyRunningException(String message) {
        super("SYNC_ALREADY_RUNNING", message);
    }
}
