package com.dropfolio.common.exception;

public class SteamNotLinkedException extends ConflictException {
    public SteamNotLinkedException(String message) {
        super("STEAM_NOT_LINKED", message);
    }
}
