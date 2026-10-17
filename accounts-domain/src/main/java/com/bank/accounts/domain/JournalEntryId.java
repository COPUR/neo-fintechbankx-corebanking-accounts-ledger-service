package com.bank.accounts.domain;

import com.bank.shared.kernel.domain.ValueObject;

import java.util.UUID;

/** Id of one booked journal entry. */
public record JournalEntryId(String value) implements ValueObject {

    public JournalEntryId {
        value = Identifiers.require(value, "journalEntryId");
    }

    public static JournalEntryId of(String value) {
        return new JournalEntryId(value);
    }

    public static JournalEntryId generate() {
        return new JournalEntryId("JE-" + UUID.randomUUID().toString().toUpperCase());
    }

    @Override
    public String toString() {
        return value;
    }
}
