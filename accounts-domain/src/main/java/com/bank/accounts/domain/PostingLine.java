package com.bank.accounts.domain;

import com.bank.shared.kernel.domain.Money;
import com.bank.shared.kernel.domain.ValueObject;

import java.util.Objects;

/** One line of a journal entry: an account, a side and a positive amount. */
public record PostingLine(AccountId accountId, PostingSide side, Money amount) implements ValueObject {

    public PostingLine {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(side, "side");
        Objects.requireNonNull(amount, "amount");
        if (!amount.isPositive()) {
            throw new IllegalArgumentException("A posting amount must be positive: " + amount);
        }
    }

    public static PostingLine debit(AccountId accountId, Money amount) {
        return new PostingLine(accountId, PostingSide.DEBIT, amount);
    }

    public static PostingLine credit(AccountId accountId, Money amount) {
        return new PostingLine(accountId, PostingSide.CREDIT, amount);
    }
}
