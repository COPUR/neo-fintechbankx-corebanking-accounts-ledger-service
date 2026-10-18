package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.AccountType;
import com.bank.shared.kernel.domain.CustomerId;

import java.util.Currency;
import java.util.Objects;

/**
 * Opens a customer account (holder set) or an internal ledger account
 * (holder null, type INTERNAL). The request key is the caller's
 * Idempotency-Key: the same key with the same content returns the first
 * account; the same key with other content is refused.
 */
public record OpenAccountCommand(String requestKey, CustomerId holder, AccountType type, Currency currency,
                                 boolean islamicCompliant) {

    public static final int MAX_REQUEST_KEY_LENGTH = 128;

    public OpenAccountCommand {
        if (requestKey == null || requestKey.isBlank()) {
            throw new IllegalArgumentException("requestKey (Idempotency-Key) is required");
        }
        requestKey = requestKey.trim();
        if (requestKey.length() > MAX_REQUEST_KEY_LENGTH) {
            throw new IllegalArgumentException("requestKey is longer than " + MAX_REQUEST_KEY_LENGTH + " characters");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(currency, "currency");
        if ((type == AccountType.INTERNAL) != (holder == null)) {
            throw new IllegalArgumentException("An INTERNAL account has no holder; every other type needs one");
        }
        if (type == AccountType.INTERNAL && islamicCompliant) {
            throw new IllegalArgumentException("An internal account is not a customer product");
        }
    }

    public boolean internal() {
        return type == AccountType.INTERNAL;
    }
}
