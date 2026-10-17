package com.bank.accounts.domain.port.out;

import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;

import java.util.Optional;

public interface JournalEntryRepository {

    Optional<JournalEntry> findById(JournalEntryId id);

    Optional<JournalEntry> findByReference(String reference);

    JournalEntry save(JournalEntry entry);
}
