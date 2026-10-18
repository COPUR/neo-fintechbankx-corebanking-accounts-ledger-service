package com.bank.accounts.infrastructure.persistence;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Adapter logic without a database (the SQL itself is covered by
 * AccountsLedgerServiceIT against PostgreSQL): mapping both ways, lock
 * order, version checks.
 */
class JpaLedgerAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");
    private static final Currency AED = Currency.getInstance("AED");

    private final SpringDataAccountRepository accountRows = mock(SpringDataAccountRepository.class);
    private final SpringDataJournalEntryRepository entryRows = mock(SpringDataJournalEntryRepository.class);
    private final JpaAccountRepositoryAdapter accounts = new JpaAccountRepositoryAdapter(accountRows);
    private final JpaJournalEntryRepositoryAdapter entries = new JpaJournalEntryRepositoryAdapter(entryRows);

    @Test
    void aCustomerAccountSurvivesTheRoundTripThroughItsRow() {
        Account account = customerAccount("ACC-1");
        account.credit(Money.of("25.50", AED), JournalEntryId.of("JE-1"), NOW);
        account.freeze("court order", NOW);

        AccountJpaEntity row = LedgerPersistenceMapper.newRow(account, "open-1");
        Account back = LedgerPersistenceMapper.toDomain(row);

        assertThat(row.getOpenRequestKey()).isEqualTo("open-1");
        assertThat(back.getId()).isEqualTo(AccountId.of("ACC-1"));
        assertThat(back.holder()).contains(CustomerId.of("CUST-1"));
        assertThat(back.type()).isEqualTo(AccountType.SAVINGS);
        assertThat(back.status()).isEqualTo(AccountStatus.FROZEN);
        assertThat(back.freezeReason()).contains("court order");
        assertThat(back.bookedBalance()).isEqualTo(Money.of("25.50", AED));
        assertThat(back.islamicCompliant()).isTrue();
        assertThat(back.openedAt()).isEqualTo(NOW);
    }

    @Test
    void anInternalAccountHasNoHolderRow() {
        Account clearing = Account.openInternalAccount(AccountId.of("ACC-INT"), AED, NOW);

        Account back = LedgerPersistenceMapper.toDomain(LedgerPersistenceMapper.newRow(clearing, null));

        assertThat(back.holder()).isEmpty();
        assertThat(back.type()).isEqualTo(AccountType.INTERNAL);
    }

    @Test
    void accountsAreLockedOnceEachInAscendingIdOrder() {
        when(accountRows.lockAllInIdOrder(List.of("ACC-1", "ACC-2"))).thenReturn(List.of(
            LedgerPersistenceMapper.newRow(customerAccount("ACC-1"), null),
            LedgerPersistenceMapper.newRow(customerAccount("ACC-2"), null)));

        List<Account> locked = accounts.lockForPosting(
            List.of(AccountId.of("ACC-2"), AccountId.of("ACC-1"), AccountId.of("ACC-2")));

        assertThat(locked).extracting(Account::getId).containsExactly(AccountId.of("ACC-1"), AccountId.of("ACC-2"));
    }

    @Test
    void findersMapStoredRows() {
        AccountJpaEntity row = LedgerPersistenceMapper.newRow(customerAccount("ACC-3"), "open-3");
        when(accountRows.findById("ACC-3")).thenReturn(Optional.of(row));
        when(accountRows.findByOpenRequestKey("open-3")).thenReturn(Optional.of(row));

        assertThat(accounts.findById(AccountId.of("ACC-3"))).map(Account::getId).contains(AccountId.of("ACC-3"));
        assertThat(accounts.findByOpenRequestKey("open-3")).isPresent();
        assertThat(accounts.findById(AccountId.of("ACC-missing"))).isEmpty();
    }

    @Test
    void aNewAccountIsInsertedWithItsOpenRequestKey() {
        Account account = customerAccount("ACC-4");
        when(accountRows.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(accounts.save(account, "open-4")).isSameAs(account);
        verify(accountRows).saveAndFlush(any(AccountJpaEntity.class));
    }

    @Test
    void anExistingRowTakesTheNewBalanceWhenTheVersionMatches() {
        AccountJpaEntity stored = LedgerPersistenceMapper.newRow(customerAccount("ACC-5"), null);
        Account current = LedgerPersistenceMapper.toDomain(stored);
        current.credit(Money.of("10.00", AED), JournalEntryId.of("JE-5"), NOW);
        when(accountRows.findById("ACC-5")).thenReturn(Optional.of(stored));
        when(accountRows.saveAndFlush(stored)).thenReturn(stored);

        accounts.save(current);

        assertThat(LedgerPersistenceMapper.toDomain(stored).bookedBalance()).isEqualTo(Money.of("10.00", AED));
    }

    @Test
    void aChangeMadeOnAnOlderVersionIsRefused() {
        AccountJpaEntity stored = LedgerPersistenceMapper.newRow(customerAccount("ACC-6"), null);
        Account stale = customerAccount("ACC-6");
        stale.setVersion(7L);
        when(accountRows.findById("ACC-6")).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> accounts.save(stale))
            .isInstanceOf(OptimisticLockingFailureException.class)
            .hasMessageContaining("ACC-6");
        verify(accountRows, never()).saveAndFlush(any());
    }

    @Test
    void updatingAnAccountThatWasNeverStoredIsADefect() {
        when(accountRows.findById("ACC-7")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accounts.save(customerAccount("ACC-7")))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aJournalEntryKeepsItsLinesInOrder() {
        JournalEntry entry = JournalEntry.book(JournalEntryId.of("JE-8"), "PAY-8", List.of(
            PostingLine.debit(AccountId.of("ACC-1"), Money.of("40.00", AED)),
            PostingLine.credit(AccountId.of("ACC-2"), Money.of("30.00", AED)),
            PostingLine.credit(AccountId.of("ACC-3"), Money.of("10.00", AED))), "split", NOW);
        when(entryRows.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(entries.save(entry)).isSameAs(entry);
        JournalEntry back = LedgerPersistenceMapper.toDomain(LedgerPersistenceMapper.toRow(entry));

        assertThat(back.sameContentAs(entry)).isTrue();
        assertThat(back.lines()).isEqualTo(entry.lines());
        assertThat(back.narrative()).isEqualTo("split");
        assertThat(back.bookedAt()).isEqualTo(NOW);
    }

    @Test
    void entryFindersMapStoredRows() {
        JournalEntry entry = JournalEntry.book(JournalEntryId.of("JE-9"), "PAY-9", List.of(
            PostingLine.debit(AccountId.of("ACC-1"), Money.of("1.00", AED)),
            PostingLine.credit(AccountId.of("ACC-2"), Money.of("1.00", AED))), null, NOW);
        JournalEntryJpaEntity row = LedgerPersistenceMapper.toRow(entry);
        when(entryRows.findById("JE-9")).thenReturn(Optional.of(row));
        when(entryRows.findByReference("PAY-9")).thenReturn(Optional.of(row));

        assertThat(entries.findById(JournalEntryId.of("JE-9"))).map(JournalEntry::reference).contains("PAY-9");
        assertThat(entries.findByReference("PAY-9")).isPresent();
        assertThat(entries.findByReference("PAY-none")).isEmpty();
    }

    private static Account customerAccount(String id) {
        return Account.openCustomerAccount(AccountId.of(id), CustomerId.of("CUST-1"), AccountType.SAVINGS, AED,
            true, NOW);
    }
}
