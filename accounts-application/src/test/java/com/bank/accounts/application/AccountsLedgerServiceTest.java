package com.bank.accounts.application;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountEvent;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.exception.AccountNotFoundException;
import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.IdempotencyConflictException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.accounts.domain.port.in.Caller;
import com.bank.accounts.domain.port.in.OpenAccountCommand;
import com.bank.accounts.domain.port.in.PostJournalEntryCommand;
import com.bank.accounts.domain.port.in.PostingResult;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountsLedgerServiceTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);

    private InMemoryStores stores;
    private RecordingTransactions transactions;
    private AccountsLedgerService service;
    private Account clearing;
    private Account alice;
    private Account bob;

    @BeforeEach
    void setUp() {
        stores = new InMemoryStores();
        transactions = new RecordingTransactions();
        service = new AccountsLedgerService(stores, stores, stores, transactions, CLOCK);
        clearing = service.open(new OpenAccountCommand("k-gl", null, AccountType.INTERNAL, AED, false));
        alice = service.open(new OpenAccountCommand("k-a", CustomerId.of("CUST-A"), AccountType.CURRENT, AED, false));
        bob = service.open(new OpenAccountCommand("k-b", CustomerId.of("CUST-B"), AccountType.SAVINGS, AED, true));
        stores.published.clear();
    }

    private static Money aed(String amount) {
        return Money.of(amount, AED);
    }

    private PostingResult fund(Account account, String amount, String reference) {
        return service.post(new PostJournalEntryCommand(reference, List.of(
            PostingLine.debit(clearing.getId(), aed(amount)), PostingLine.credit(account.getId(), aed(amount))),
            "funding"));
    }

    @Test
    void openingPublishesTheOpenedEventAndAReplayReturnsTheSameAccount() {
        OpenAccountCommand command = new OpenAccountCommand("k-c", CustomerId.of("CUST-C"), AccountType.CURRENT,
            AED, false);

        Account first = service.open(command);
        Account replay = service.open(command);

        assertThat(replay.getId()).isEqualTo(first.getId());
        assertThat(stores.published).singleElement().isInstanceOf(AccountEvent.Opened.class);
    }

    @Test
    void reusingAnOpenRequestKeyForOtherContentIsAConflict() {
        assertThatThrownBy(() -> service.open(
            new OpenAccountCommand("k-a", CustomerId.of("CUST-A"), AccountType.SAVINGS, AED, false)))
            .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aPostingMovesBothBalancesStoresTheEntryAndPublishesOneEventPerLine() {
        fund(alice, "100.00", "FUND-1");

        PostingResult result = service.post(new PostJournalEntryCommand("PAY-1", List.of(
            PostingLine.debit(alice.getId(), aed("40.00")), PostingLine.credit(bob.getId(), aed("40.00"))),
            "payment PAY-1"));

        assertThat(result.replay()).isFalse();
        assertThat(stores.accounts.get(alice.getId()).bookedBalance()).isEqualTo(aed("60.00"));
        assertThat(stores.accounts.get(bob.getId()).bookedBalance()).isEqualTo(aed("40.00"));
        assertThat(stores.entries).containsKey(result.entry().getId());
        assertThat(stores.published).hasSize(4);
        assertThat(stores.published).filteredOn(AccountEvent.Debited.class::isInstance).hasSize(2);
    }

    @Test
    void theSumOfAllBalancesStaysZero() {
        fund(alice, "100.00", "FUND-1");
        fund(bob, "25.50", "FUND-2");
        service.post(new PostJournalEntryCommand("PAY-1", List.of(
            PostingLine.debit(alice.getId(), aed("10.00")), PostingLine.credit(bob.getId(), aed("10.00"))), null));

        Money total = stores.accounts.values().stream().map(Account::bookedBalance)
            .reduce(Money.zero(AED), Money::add);

        assertThat(total).isEqualTo(Money.zero(AED));
    }

    @Test
    void accountsAreLockedOnceEachInIdOrder() {
        fund(alice, "100.00", "FUND-1");
        fund(bob, "1.00", "FUND-2");

        service.post(new PostJournalEntryCommand("PAY-1", List.of(
            PostingLine.debit(bob.getId(), aed("0.01")),
            PostingLine.debit(alice.getId(), aed("1.00")),
            PostingLine.credit(clearing.getId(), aed("1.01"))), null));

        List<AccountId> order = stores.lockOrders.get(stores.lockOrders.size() - 1);
        assertThat(order).isSortedAccordingTo(java.util.Comparator.comparing(AccountId::value));
        assertThat(order).hasSize(3);
    }

    @Test
    void aReplayOfTheSameReferenceAndLinesDoesNotBookTwice() {
        PostingResult first = fund(alice, "100.00", "FUND-1");
        stores.published.clear();

        PostingResult replay = fund(alice, "100.00", "FUND-1");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.entry().getId()).isEqualTo(first.entry().getId());
        assertThat(stores.accounts.get(alice.getId()).bookedBalance()).isEqualTo(aed("100.00"));
        assertThat(stores.published).isEmpty();
    }

    @Test
    void theSameReferenceWithOtherLinesIsAConflict() {
        fund(alice, "100.00", "FUND-1");

        assertThatThrownBy(() -> fund(alice, "100.01", "FUND-1")).isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void anOverdraftOnACustomerAccountBooksNothing() {
        fund(alice, "10.00", "FUND-1");

        assertThatThrownBy(() -> service.post(new PostJournalEntryCommand("PAY-1", List.of(
            PostingLine.debit(alice.getId(), aed("10.01")), PostingLine.credit(bob.getId(), aed("10.01"))), null)))
            .isInstanceOf(InsufficientFundsException.class);

        assertThat(stores.findByReference("PAY-1")).isEmpty();
        assertThat(stores.published).filteredOn(AccountEvent.Credited.class::isInstance)
            .noneMatch(event -> ((AccountEvent.Credited) event).accountId().equals(bob.getId()));
    }

    @Test
    void anUnknownAccountInAPostingIsReportedAndNothingIsBooked() {
        assertThatThrownBy(() -> service.post(new PostJournalEntryCommand("PAY-1", List.of(
            PostingLine.debit(clearing.getId(), aed("1.00")),
            PostingLine.credit(AccountId.of("ACC-UNKNOWN"), aed("1.00"))), null)))
            .isInstanceOf(AccountNotFoundException.class)
            .hasMessageContaining("ACC-UNKNOWN");
        assertThat(stores.entries).isEmpty();
    }

    @Test
    void aFrozenAccountTakesNoPostings() {
        service.freeze(bob.getId(), "court order");

        assertThatThrownBy(() -> fund(bob, "1.00", "FUND-9")).isInstanceOf(AccountNotOperationalException.class);
    }

    @Test
    void freezeUnfreezeAndClosePublishTheirEvents() {
        Account frozen = service.freeze(bob.getId(), "court order");
        assertThat(frozen.status()).isEqualTo(AccountStatus.FROZEN);

        service.unfreeze(bob.getId());
        Account closed = service.close(bob.getId());

        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(stores.published).hasSize(3);
        assertThat(stores.published.get(2)).isInstanceOf(AccountEvent.Closed.class);
    }

    @Test
    void manageOnAnUnknownAccountIsNotFound() {
        assertThatThrownBy(() -> service.freeze(AccountId.of("ACC-X"), "x"))
            .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void aCustomerSeesOnlyTheirOwnAccountsAndAnotherOneLooksUnknown() {
        assertThat(service.find(alice.getId(), Caller.customer("CUST-A")).getId()).isEqualTo(alice.getId());
        assertThat(service.find(alice.getId(), Caller.staff("svc-pay-initiation-settlement")).getId())
            .isEqualTo(alice.getId());

        assertThatThrownBy(() -> service.find(alice.getId(), Caller.customer("CUST-B")))
            .isInstanceOf(AccountNotFoundException.class)
            .hasMessage(new AccountNotFoundException(alice.getId()).getMessage());
        assertThatThrownBy(() -> service.find(clearing.getId(), Caller.customer("CUST-A")))
            .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void journalEntriesCanBeReadBack() {
        PostingResult funded = fund(alice, "5.00", "FUND-1");

        assertThat(service.find(funded.entry().getId()).reference()).isEqualTo("FUND-1");
        assertThatThrownBy(() -> service.find(com.bank.accounts.domain.JournalEntryId.of("JE-X")))
            .isInstanceOf(JournalEntryNotFoundException.class);
    }

    @Test
    void everyWriteRunsInOneTransaction() {
        int before = transactions.opened();

        fund(alice, "5.00", "FUND-1");
        service.freeze(alice.getId(), "x");

        assertThat(transactions.opened()).isEqualTo(before + 2);
    }
}
