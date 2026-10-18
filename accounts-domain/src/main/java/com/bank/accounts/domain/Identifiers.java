package com.bank.accounts.domain;

/** Shared checks for the string identifiers of this context. */
final class Identifiers {

    static final int MAX_LENGTH = 64;

    private Identifiers() {
    }

    static String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        String trimmed = value.trim();
        if (trimmed.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(name + " is longer than " + MAX_LENGTH + " characters");
        }
        return trimmed;
    }
}
