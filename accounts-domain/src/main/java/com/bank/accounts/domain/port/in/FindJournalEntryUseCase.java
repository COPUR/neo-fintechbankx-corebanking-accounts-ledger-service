package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;

public interface FindJournalEntryUseCase {

    JournalEntry find(JournalEntryId id);
}
