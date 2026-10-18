package com.bank.accounts.domain;

import com.bank.accounts.domain.exception.AccountNotOperationalException;
import com.bank.accounts.domain.exception.InsufficientFundsException;
import com.bank.shared.kernel.domain.AggregateRoot;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.Optional;

/**
 * Account aggregate, extracted from AmanahFi's accounts context. The balance
 * changes only through {@link #debit} and {@link #credit}, which a booked
 * {@link JournalEntry} drives; there is no deposit or withdraw without a
 * counter entry. Holds are not modelled yet, so the available balance is the
 * booked balance.
 */
public final class Account extends AggregateRoot<AccountId> {

    private final AccountId id;
    private final AccountCategory category;
    private final CustomerId holder;
    private final AccountType type;
    private final Currency currency;
    private final boolean islamicCompliant;
    private final Instant openedAt;
    private AccountStatus status;
    private String freezeReason;
    private Money bookedBalance;
    private Instant updatedAt;

    private Account(AccountId id, AccountCategory category, CustomerId holder, AccountType type, Currency currency,
                    AccountStatus status, boolean islamicCompliant, String freezeReason, Money bookedBalance,
                    Instant openedAt, Instant updatedAt) {
        this.id = Objects.requireNonNull(id, "accountId");
        this.category = Objects.requireNonNull(category, "category");
        this.type = Objects.requireNonNull(type, "type");
        this.currency = Objects.requireNonNull(currency, "currency");
        this.status = Objects.requireNonNull(status, "status");
        this.bookedBalance = Objects.requireNonNull(bookedBalance, "bookedBalance");
        this.openedAt = Objects.requireNonNull(openedAt, "openedAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        if (category == AccountCategory.CUSTOMER) {
            this.holder = Objects.requireNonNull(holder, "a customer account needs a holder");
            if (type == AccountType.INTERNAL) {
                throw new IllegalArgumentException("A customer account cannot have the INTERNAL type");
            }
        } else {
            if (holder != null || type != AccountType.INTERNAL) {
                throw new IllegalArgumentException("An internal account has no holder and the INTERNAL type");
            }
            this.holder = null;
        }
        if (!bookedBalance.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("The balance currency must be the account currency");
        }
        this.islamicCompliant = islamicCompliant;
        this.freezeReason = freezeReason;
    }

    public static Account openCustomerAccount(AccountId id, CustomerId holder, AccountType type, Currency currency,
                                              boolean islamicCompliant, Instant now) {
        Account account = new Account(id, AccountCategory.CUSTOMER, holder, type, currency, AccountStatus.ACTIVE,
            islamicCompliant, null, Money.zero(currency), now, now);
        account.addDomainEvent(AccountEvent.Opened.of(account, now));
        return account;
    }

    public static Account openInternalAccount(AccountId id, Currency currency, Instant now) {
        Account account = new Account(id, AccountCategory.INTERNAL, null, AccountType.INTERNAL, currency,
            AccountStatus.ACTIVE, false, null, Money.zero(currency), now, now);
        account.addDomainEvent(AccountEvent.Opened.of(account, now));
        return account;
    }

    /** Restores a stored account; raises no events. */
    public static Account rehydrate(AccountId id, AccountCategory category, CustomerId holder, AccountType type,
                                    Currency currency, AccountStatus status, boolean islamicCompliant,
                                    String freezeReason, Money bookedBalance, Instant openedAt, Instant updatedAt,
                                    Long version) {
        Account account = new Account(id, category, holder, type, currency, status, islamicCompliant, freezeReason,
            bookedBalance, openedAt, updatedAt);
        account.setVersion(version);
        return account;
    }

    public void debit(Money amount, JournalEntryId entry, Instant now) {
        requirePostable(amount);
        Money after = bookedBalance.subtract(amount);
        if (category == AccountCategory.CUSTOMER && after.isNegative()) {
            throw new InsufficientFundsException(id, bookedBalance, amount);
        }
        bookedBalance = after;
        updatedAt = now;
        addDomainEvent(AccountEvent.Debited.of(id, entry, amount, after, now));
    }

    public void credit(Money amount, JournalEntryId entry, Instant now) {
        requirePostable(amount);
        bookedBalance = bookedBalance.add(amount);
        updatedAt = now;
        addDomainEvent(AccountEvent.Credited.of(id, entry, amount, bookedBalance, now));
    }

    public void freeze(String reason, Instant now) {
        if (status == AccountStatus.CLOSED) {
            throw new IllegalStateException("A closed account cannot be frozen: " + id);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A freeze needs a reason");
        }
        status = AccountStatus.FROZEN;
        freezeReason = reason.trim();
        updatedAt = now;
        addDomainEvent(AccountEvent.Frozen.of(id, freezeReason, now));
    }

    public void unfreeze(Instant now) {
        if (status != AccountStatus.FROZEN) {
            throw new IllegalStateException("Only a frozen account can be unfrozen: " + id);
        }
        status = AccountStatus.ACTIVE;
        freezeReason = null;
        updatedAt = now;
        addDomainEvent(AccountEvent.Unfrozen.of(id, now));
    }

    public void close(Instant now) {
        if (status == AccountStatus.CLOSED) {
            return;
        }
        if (!bookedBalance.isZero()) {
            throw new IllegalStateException("Only a zero-balance account can be closed; " + id + " holds "
                + bookedBalance);
        }
        status = AccountStatus.CLOSED;
        freezeReason = null;
        updatedAt = now;
        addDomainEvent(AccountEvent.Closed.of(id, now));
    }

    private void requirePostable(Money amount) {
        if (status != AccountStatus.ACTIVE) {
            throw new AccountNotOperationalException(id, status);
        }
        if (!amount.getCurrency().equals(currency)) {
            throw new IllegalArgumentException("Posting currency " + amount.getCurrency() + " differs from account "
                + id + " currency " + currency);
        }
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A posting amount must be positive: " + amount);
        }
    }

    @Override
    public AccountId getId() {
        return id;
    }

    public AccountCategory category() {
        return category;
    }

    public Optional<CustomerId> holder() {
        return Optional.ofNullable(holder);
    }

    public boolean heldBy(CustomerId customerId) {
        return holder != null && holder.equals(customerId);
    }

    public AccountType type() {
        return type;
    }

    public Currency currency() {
        return currency;
    }

    public AccountStatus status() {
        return status;
    }

    public boolean islamicCompliant() {
        return islamicCompliant;
    }

    public Optional<String> freezeReason() {
        return Optional.ofNullable(freezeReason);
    }

    public Money bookedBalance() {
        return bookedBalance;
    }

    /** No holds yet, so the same as the booked balance. */
    public Money availableBalance() {
        return bookedBalance;
    }

    public boolean debitAllowed() {
        return status == AccountStatus.ACTIVE;
    }

    public boolean creditAllowed() {
        return status == AccountStatus.ACTIVE;
    }

    public Instant openedAt() {
        return openedAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
