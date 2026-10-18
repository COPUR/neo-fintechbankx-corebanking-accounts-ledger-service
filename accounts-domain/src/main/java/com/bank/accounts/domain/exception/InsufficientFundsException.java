package com.bank.accounts.domain.exception;

import com.bank.accounts.domain.AccountId;
import com.bank.shared.kernel.domain.Money;

/** A customer account would go below zero. */
public class InsufficientFundsException extends RuntimeException {

    private final AccountId accountId;

    public InsufficientFundsException(AccountId accountId, Money available, Money requested) {
        super("Insufficient funds on " + accountId + ": available " + available + ", requested " + requested);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
