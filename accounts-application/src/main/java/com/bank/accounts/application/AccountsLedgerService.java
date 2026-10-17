package com.bank.accounts.application;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.exception.AccountNotFoundException;
import com.bank.accounts.domain.exception.IdempotencyConflictException;
import com.bank.accounts.domain.exception.JournalEntryNotFoundException;
import com.bank.accounts.domain.port.in.Caller;
import com.bank.accounts.domain.port.in.FindAccountUseCase;
import com.bank.accounts.domain.port.in.FindJournalEntryUseCase;
import com.bank.accounts.domain.port.in.ManageAccountUseCase;
import com.bank.accounts.domain.port.in.OpenAccountCommand;
import com.bank.accounts.domain.port.in.OpenAccountUseCase;
import com.bank.accounts.domain.port.in.PostJournalEntryCommand;
import com.bank.accounts.domain.port.in.PostJournalEntryUseCase;
import com.bank.accounts.domain.port.in.PostingResult;
import com.bank.accounts.domain.port.out.AccountEventPort;
import com.bank.accounts.domain.port.out.AccountRepository;
import com.bank.accounts.domain.port.out.JournalEntryRepository;
import com.bank.shared.kernel.domain.DomainEvent;
import org.springframework.transaction.support.TransactionOperations;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Use cases of the accounts ledger. Every write runs in one transaction:
 * the entry, the account balances and their outbox events commit together
 * or not at all.
 *
 * Posting order: lock every touched account (ascending id), then look for an
 * earlier entry with the same reference. A concurrent post of the same
 * reference touches the same accounts, so it waits on the locks and then
 * sees the first entry; the unique reference column is the backstop.
 */
public class AccountsLedgerService implements OpenAccountUseCase, FindAccountUseCase, ManageAccountUseCase,
    PostJournalEntryUseCase, FindJournalEntryUseCase {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final AccountEventPort events;
    private final TransactionOperations transactions;
    private final Clock clock;

    public AccountsLedgerService(AccountRepository accounts, JournalEntryRepository entries, AccountEventPort events,
                                 TransactionOperations transactions, Clock clock) {
        this.accounts = Objects.requireNonNull(accounts);
        this.entries = Objects.requireNonNull(entries);
        this.events = Objects.requireNonNull(events);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Account open(OpenAccountCommand command) {
        return transactions.execute(status -> {
            Optional<Account> earlier = accounts.findByOpenRequestKey(command.requestKey());
            if (earlier.isPresent()) {
                return replayOf(earlier.get(), command);
            }
            Instant now = clock.instant();
            Account account = command.internal()
                ? Account.openInternalAccount(AccountId.generate(), command.currency(), now)
                : Account.openCustomerAccount(AccountId.generate(), command.holder(), command.type(),
                    command.currency(), command.islamicCompliant(), now);
            Account saved = accounts.save(account, command.requestKey());
            publish(account);
            return saved;
        });
    }

    private static Account replayOf(Account earlier, OpenAccountCommand command) {
        boolean same = earlier.type() == command.type()
            && earlier.currency().equals(command.currency())
            && earlier.islamicCompliant() == command.islamicCompliant()
            && Objects.equals(earlier.holder().orElse(null), command.holder());
        if (!same) {
            throw new IdempotencyConflictException(
                "Idempotency-Key " + command.requestKey() + " already opened a different account");
        }
        return earlier;
    }

    @Override
    public Account find(AccountId accountId, Caller caller) {
        Account account = accounts.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!caller.staff() && account.holder().map(holder -> !caller.mayActFor(holder)).orElse(true)) {
            throw new AccountNotFoundException(accountId);
        }
        return account;
    }

    @Override
    public Account freeze(AccountId accountId, String reason) {
        return change(accountId, account -> account.freeze(reason, clock.instant()));
    }

    @Override
    public Account unfreeze(AccountId accountId) {
        return change(accountId, account -> account.unfreeze(clock.instant()));
    }

    @Override
    public Account close(AccountId accountId) {
        return change(accountId, account -> account.close(clock.instant()));
    }

    private Account change(AccountId accountId, Consumer<Account> change) {
        return transactions.execute(status -> {
            Account account = accounts.lockForPosting(List.of(accountId)).stream().findFirst()
                .orElseThrow(() -> new AccountNotFoundException(accountId));
            change.accept(account);
            Account saved = accounts.save(account);
            publish(account);
            return saved;
        });
    }

    @Override
    public PostingResult post(PostJournalEntryCommand command) {
        Instant now = clock.instant();
        JournalEntry requested = JournalEntry.book(JournalEntryId.generate(), command.reference(), command.lines(),
            command.narrative(), now);
        return transactions.execute(status -> {
            Map<AccountId, Account> locked = accounts.lockForPosting(requested.accountIds()).stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));
            Optional<JournalEntry> earlier = entries.findByReference(requested.reference());
            if (earlier.isPresent()) {
                if (!earlier.get().sameContentAs(requested)) {
                    throw new IdempotencyConflictException(
                        "Reference " + requested.reference() + " was already booked with other lines");
                }
                return new PostingResult(earlier.get(), true);
            }
            for (AccountId id : requested.accountIds()) {
                if (!locked.containsKey(id)) {
                    throw new AccountNotFoundException(id);
                }
            }
            for (PostingLine line : requested.lines()) {
                Account account = locked.get(line.accountId());
                switch (line.side()) {
                    case DEBIT -> account.debit(line.amount(), requested.getId(), now);
                    case CREDIT -> account.credit(line.amount(), requested.getId(), now);
                }
            }
            JournalEntry saved = entries.save(requested);
            for (AccountId id : requested.accountIds()) {
                Account account = locked.get(id);
                accounts.save(account);
                publish(account);
            }
            return new PostingResult(saved, false);
        });
    }

    @Override
    public JournalEntry find(JournalEntryId id) {
        return entries.findById(id).orElseThrow(() -> new JournalEntryNotFoundException(id));
    }

    private void publish(Account account) {
        List<DomainEvent> pending = List.copyOf(account.getDomainEvents());
        if (!pending.isEmpty()) {
            events.publish(account, pending);
            account.clearDomainEvents();
        }
    }
}
