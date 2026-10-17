package com.bank.accounts.infrastructure.persistence;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountCategory;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.domain.PostingSide;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;

import java.util.Currency;

/** Maps the ledger aggregates to and from their rows. */
final class LedgerPersistenceMapper {

    private LedgerPersistenceMapper() {
    }

    static AccountJpaEntity newRow(Account account, String openRequestKey) {
        AccountJpaEntity row = new AccountJpaEntity(account.getId().value(), account.category().name(),
            account.holder().map(CustomerId::getValue).orElse(null), account.type().name(),
            account.currency().getCurrencyCode(), account.islamicCompliant(), openRequestKey, account.openedAt());
        copyState(account, row);
        return row;
    }

    static void copyState(Account account, AccountJpaEntity row) {
        row.updateState(account.status().name(), account.freezeReason().orElse(null),
            account.bookedBalance().getAmount(), account.updatedAt());
    }

    static Account toDomain(AccountJpaEntity row) {
        Currency currency = Currency.getInstance(row.getCurrency());
        return Account.rehydrate(AccountId.of(row.getAccountId()), AccountCategory.valueOf(row.getCategory()),
            row.getHolderId() == null ? null : CustomerId.of(row.getHolderId()),
            AccountType.valueOf(row.getAccountType()), currency, AccountStatus.valueOf(row.getStatus()),
            row.isIslamicCompliant(), row.getFreezeReason(), Money.of(row.getBookedBalance(), currency),
            row.getOpenedAt(), row.getUpdatedAt(), row.getVersion());
    }

    static JournalEntryJpaEntity toRow(JournalEntry entry) {
        return new JournalEntryJpaEntity(entry.getId().value(), entry.reference(),
            entry.currency().getCurrencyCode(), entry.narrative(), entry.bookedAt(),
            entry.lines().stream().map(line -> new PostingLineEmbeddable(line.accountId().value(),
                line.side().name(), line.amount().getAmount())).toList());
    }

    static JournalEntry toDomain(JournalEntryJpaEntity row) {
        Currency currency = Currency.getInstance(row.getCurrency());
        return JournalEntry.rehydrate(JournalEntryId.of(row.getJournalEntryId()), row.getReference(),
            row.getLines().stream().map(line -> new PostingLine(AccountId.of(line.getAccountId()),
                PostingSide.valueOf(line.getSide()), Money.of(line.getAmount(), currency))).toList(),
            row.getNarrative(), row.getBookedAt());
    }
}
