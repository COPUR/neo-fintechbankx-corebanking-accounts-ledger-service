package com.bank.accounts.infrastructure.persistence;

import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.port.out.JournalEntryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** Journal entry out-port on Postgres; entries are inserted, never updated. */
@Component
public class JpaJournalEntryRepositoryAdapter implements JournalEntryRepository {

    private final SpringDataJournalEntryRepository rows;

    public JpaJournalEntryRepositoryAdapter(SpringDataJournalEntryRepository rows) {
        this.rows = rows;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findById(JournalEntryId id) {
        return rows.findById(id.value()).map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<JournalEntry> findByReference(String reference) {
        return rows.findByReference(reference).map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public JournalEntry save(JournalEntry entry) {
        rows.saveAndFlush(LedgerPersistenceMapper.toRow(entry));
        return entry;
    }
}
