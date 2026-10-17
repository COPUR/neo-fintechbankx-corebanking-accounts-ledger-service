package com.bank.accounts.domain;

import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;

import java.time.Instant;
import java.util.UUID;

/**
 * Events of the Account aggregate, published on evt.acc.account.v1 keyed by
 * the account id. Each carries a fixed event id and time, so a retry of the
 * outbox write never changes them.
 */
public sealed interface AccountEvent extends DomainEvent {

    AccountId accountId();

    String eventId();

    Instant occurredAt();

    @Override
    default String getEventId() {
        return eventId();
    }

    @Override
    default Instant getOccurredOn() {
        return occurredAt();
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    record Opened(String eventId, Instant occurredAt, AccountId accountId, AccountCategory category,
                  CustomerId holder, AccountType type, String currency, boolean islamicCompliant)
        implements AccountEvent {
        static Opened of(Account account, Instant at) {
            return new Opened(newId(), at, account.getId(), account.category(), account.holder().orElse(null),
                account.type(), account.currency().getCurrencyCode(), account.islamicCompliant());
        }
    }

    record Frozen(String eventId, Instant occurredAt, AccountId accountId, String reason) implements AccountEvent {
        static Frozen of(AccountId accountId, String reason, Instant at) {
            return new Frozen(newId(), at, accountId, reason);
        }
    }

    record Unfrozen(String eventId, Instant occurredAt, AccountId accountId) implements AccountEvent {
        static Unfrozen of(AccountId accountId, Instant at) {
            return new Unfrozen(newId(), at, accountId);
        }
    }

    record Closed(String eventId, Instant occurredAt, AccountId accountId) implements AccountEvent {
        static Closed of(AccountId accountId, Instant at) {
            return new Closed(newId(), at, accountId);
        }
    }

    record Debited(String eventId, Instant occurredAt, AccountId accountId, JournalEntryId journalEntryId,
                   Money amount, Money balanceAfter) implements AccountEvent {
        static Debited of(AccountId accountId, JournalEntryId entry, Money amount, Money balanceAfter, Instant at) {
            return new Debited(newId(), at, accountId, entry, amount, balanceAfter);
        }
    }

    record Credited(String eventId, Instant occurredAt, AccountId accountId, JournalEntryId journalEntryId,
                    Money amount, Money balanceAfter) implements AccountEvent {
        static Credited of(AccountId accountId, JournalEntryId entry, Money amount, Money balanceAfter, Instant at) {
            return new Credited(newId(), at, accountId, entry, amount, balanceAfter);
        }
    }
}
