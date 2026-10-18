package com.bank.accounts.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpringDataJournalEntryRepository extends JpaRepository<JournalEntryJpaEntity, String> {

    Optional<JournalEntryJpaEntity> findByReference(String reference);
}
