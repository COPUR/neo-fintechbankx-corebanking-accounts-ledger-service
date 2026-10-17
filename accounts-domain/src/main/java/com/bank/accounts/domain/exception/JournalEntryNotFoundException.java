package com.bank.accounts.domain.exception;

import com.bank.accounts.domain.JournalEntryId;

/** No journal entry has this id. */
public class JournalEntryNotFoundException extends RuntimeException {

    public JournalEntryNotFoundException(JournalEntryId id) {
        super("Journal entry not found: " + id);
    }
}
