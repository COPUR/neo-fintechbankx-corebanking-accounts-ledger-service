package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;

public interface FindJournalEntryUseCase {

    JournalEntry find(JournalEntryId id);

    /** The entry booked under the caller's business reference (trimmed, as posting stores it). */
    JournalEntry findByReference(String reference);
}
