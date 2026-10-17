package com.bank.accounts.domain;

import com.bank.shared.kernel.domain.ValueObject;

import java.util.UUID;

/**
 * Account number. Accounts carried over from the monolith keep their ids
 * (AmanahFi used ACC- plus eight hex digits); new accounts get ACC- plus a UUID.
 */
public record AccountId(String value) implements ValueObject {

    public AccountId {
        value = Identifiers.require(value, "accountId");
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    public static AccountId generate() {
        return new AccountId("ACC-" + UUID.randomUUID().toString().toUpperCase());
    }

    @Override
    public String toString() {
        return value;
    }
}
