package com.bank.accounts.domain;

import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private static Account customerAccount() {
        return Account.openCustomerAccount(AccountId.of("ACC-1"), CustomerId.of("CUST-1"), AccountType.CURRENT, AED,
            false, NOW);
    }

    @Test
    void aNewCustomerAccountIsActiveWithAZeroBalanceAndAnOpenedEvent() {
        Account account = customerAccount();

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.bookedBalance()).isEqualTo(Money.zero(AED));
        assertThat(account.availableBalance()).isEqualTo(Money.zero(AED));
        assertThat(account.holder()).contains(CustomerId.of("CUST-1"));
        assertThat(account.debitAllowed()).isTrue();
        assertThat(account.creditAllowed()).isTrue();
        assertThat(account.getDomainEvents()).singleElement().isInstanceOf(AccountEvent.Opened.class);
    }

    @Test
    void anInternalAccountHasNoHolderAndMayGoBelowZero() {
        Account clearing = Account.openInternalAccount(AccountId.of("GL-CLEARING-AED"), AED, NOW);

        clearing.debit(Money.of("100.00", AED), JournalEntryId.of("JE-1"), NOW);

        assertThat(clearing.holder()).isEmpty();
        assertThat(clearing.bookedBalance()).isEqualTo(Money.of("-100.00", AED));
    }

    @Test
    void aCustomerAccountCannotBeDebitedBelowZero() {
        Account account = customerAccount();
        account.credit(Money.of("50.00", AED), JournalEntryId.of("JE-1"), NOW);

        assertThatThrownBy(() -> account.debit(Money.of("50.01", AED), JournalEntryId.of("JE-2"), NOW))
            .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.bookedBalance()).isEqualTo(Money.of("50.00", AED));
    }

    @Test
    void creditsAndDebitsMoveTheBalanceAndRaiseEvents() {
        Account account = customerAccount();
        account.clearDomainEvents();

        account.credit(Money.of("80.00", AED), JournalEntryId.of("JE-1"), NOW);
        account.debit(Money.of("30.00", AED), JournalEntryId.of("JE-2"), NOW);

        assertThat(account.bookedBalance()).isEqualTo(Money.of("50.00", AED));
        assertThat(account.getDomainEvents()).hasSize(2);
        assertThat(account.getDomainEvents().get(0)).isInstanceOf(AccountEvent.Credited.class);
        AccountEvent.Debited debited = (AccountEvent.Debited) account.getDomainEvents().get(1);
        assertThat(debited.amount()).isEqualTo(Money.of("30.00", AED));
        assertThat(debited.balanceAfter()).isEqualTo(Money.of("50.00", AED));
        assertThat(debited.journalEntryId()).isEqualTo(JournalEntryId.of("JE-2"));
    }

    @Test
    void anAmountInAnotherCurrencyIsRefused() {
        Account account = customerAccount();

        assertThatThrownBy(() -> account.credit(Money.of("1.00", Currency.getInstance("USD")),
            JournalEntryId.of("JE-1"), NOW))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("currency");
    }

    @Test
    void aZeroOrNegativeAmountIsRefused() {
        Account account = customerAccount();

        assertThatThrownBy(() -> account.credit(Money.zero(AED), JournalEntryId.of("JE-1"), NOW))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.debit(Money.of("-1.00", AED), JournalEntryId.of("JE-1"), NOW))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFrozenAccountRefusesDebitsAndCreditsUntilUnfrozen() {
        Account account = customerAccount();

        account.freeze("sanctions review", NOW);

        assertThat(account.debitAllowed()).isFalse();
        assertThat(account.creditAllowed()).isFalse();
        assertThat(account.freezeReason()).contains("sanctions review");
        assertThatThrownBy(() -> account.credit(Money.of("1.00", AED), JournalEntryId.of("JE-1"), NOW))
            .isInstanceOf(AccountNotOperationalException.class);

        account.unfreeze(NOW);

        assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(account.freezeReason()).isEmpty();
        account.credit(Money.of("1.00", AED), JournalEntryId.of("JE-1"), NOW);
    }

    @Test
    void freezeNeedsAReasonAndOnlyAFrozenAccountCanBeUnfrozen() {
        Account account = customerAccount();

        assertThatThrownBy(() -> account.freeze(" ", NOW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> account.unfreeze(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void onlyAZeroBalanceAccountCanBeClosedAndAClosedAccountStaysClosed() {
        Account account = customerAccount();
        account.credit(Money.of("1.00", AED), JournalEntryId.of("JE-1"), NOW);

        assertThatThrownBy(() -> account.close(NOW)).isInstanceOf(IllegalStateException.class);

        account.debit(Money.of("1.00", AED), JournalEntryId.of("JE-2"), NOW);
        account.close(NOW);

        assertThat(account.status()).isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(() -> account.freeze("late", NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> account.credit(Money.of("1.00", AED), JournalEntryId.of("JE-3"), NOW))
            .isInstanceOf(AccountNotOperationalException.class);
    }

    @Test
    void anIslamicAccountIsFlaggedForProfitSharingNotInterest() {
        Account account = Account.openCustomerAccount(AccountId.of("ACC-2"), CustomerId.of("CUST-1"),
            AccountType.SAVINGS, AED, true, NOW);

        assertThat(account.islamicCompliant()).isTrue();
        assertThat(((AccountEvent.Opened) account.getDomainEvents().get(0)).islamicCompliant()).isTrue();
    }

    @Test
    void rehydrateRestoresStateWithoutEvents() {
        Account account = Account.rehydrate(AccountId.of("ACC-9"), AccountCategory.CUSTOMER, CustomerId.of("C"),
            AccountType.CURRENT, AED, AccountStatus.FROZEN, false, "hold", Money.of("12.30", AED), NOW, NOW, 4L);

        assertThat(account.getDomainEvents()).isEmpty();
        assertThat(account.getVersion()).isEqualTo(4L);
        assertThat(account.bookedBalance()).isEqualTo(Money.of("12.30", AED));
        assertThat(account.freezeReason()).contains("hold");
    }

    @Test
    void identifiersRejectBlankAndOverlongValues() {
        assertThatThrownBy(() -> AccountId.of(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AccountId.of("A".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(AccountId.of(" ACC-1 ").value()).isEqualTo("ACC-1");
        assertThat(AccountId.generate().value()).startsWith("ACC-");
        assertThat(JournalEntryId.generate().value()).startsWith("JE-");
    }

    @Test
    void eventsExposeTheirIdTimeAndAccount() {
        Account account = customerAccount();
        account.freeze("x", NOW);
        account.unfreeze(NOW);
        account.close(NOW);

        assertThat(account.getDomainEvents()).hasSize(4).allSatisfy(event -> {
            AccountEvent accountEvent = (AccountEvent) event;
            assertThat(accountEvent.getEventId()).isEqualTo(accountEvent.eventId());
            assertThat(accountEvent.getOccurredOn()).isEqualTo(NOW);
            assertThat(accountEvent.accountId()).isEqualTo(AccountId.of("ACC-1"));
        });
        account.close(NOW);
        assertThat(account.getDomainEvents()).hasSize(4);
        assertThat(account.heldBy(CustomerId.of("CUST-1"))).isTrue();
        assertThat(account.category()).isEqualTo(AccountCategory.CUSTOMER);
        assertThat(account.type()).isEqualTo(AccountType.CURRENT);
        assertThat(account.currency()).isEqualTo(AED);
        assertThat(account.openedAt()).isEqualTo(NOW);
        assertThat(account.updatedAt()).isEqualTo(NOW);
        assertThat(AccountId.of("A").toString()).isEqualTo("A");
        assertThat(JournalEntryId.of("J").toString()).isEqualTo("J");
    }

    @Test
    void aMismatchedHolderTypeOrBalanceCurrencyIsRefused() {
        assertThatThrownBy(() -> Account.rehydrate(AccountId.of("A"), AccountCategory.CUSTOMER, CustomerId.of("C"),
            AccountType.INTERNAL, AED, AccountStatus.ACTIVE, false, null, Money.zero(AED), NOW, NOW, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.rehydrate(AccountId.of("A"), AccountCategory.INTERNAL, CustomerId.of("C"),
            AccountType.INTERNAL, AED, AccountStatus.ACTIVE, false, null, Money.zero(AED), NOW, NOW, 0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Account.rehydrate(AccountId.of("A"), AccountCategory.CUSTOMER, CustomerId.of("C"),
            AccountType.CURRENT, AED, AccountStatus.ACTIVE, false, null, Money.zero(Currency.getInstance("USD")),
            NOW, NOW, 0L)).isInstanceOf(IllegalArgumentException.class);
    }
}
