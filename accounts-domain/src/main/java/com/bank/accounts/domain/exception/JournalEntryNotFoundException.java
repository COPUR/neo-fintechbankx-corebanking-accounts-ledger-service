package com.bank.accounts.domain.exception;

import com.bank.accounts.domain.JournalEntryId;

/** No journal entry has this id, or none was booked under this reference. */
public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(JournalEntryId id) {
        super("Journal entry not found: " + id);
    }

    public static JournalEntryNotFoundException forReference(String reference) {
        return new JournalEntryNotFoundException("Journal entry not found for reference " + reference);
    }

    private JournalEntryNotFoundException(String message) {
        super(message);
    }
}
