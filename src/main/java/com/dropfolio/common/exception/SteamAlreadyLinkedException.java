package com.dropfolio.common.exception;

/** User yang sedang login sudah punya steam_accounts terhubung. */
public class SteamAlreadyLinkedException extends ConflictException {
    public SteamAlreadyLinkedException(String message) {
        super("STEAM_ALREADY_LINKED", message);
    }
}
