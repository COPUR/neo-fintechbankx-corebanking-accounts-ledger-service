package com.bank.accounts.domain.exception;

import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountStatus;

/** A frozen or closed account was asked to take a posting. */
public class AccountNotOperationalException extends RuntimeException {

    private final AccountId accountId;
    private final AccountStatus status;

    public AccountNotOperationalException(AccountId accountId, AccountStatus status) {
        super("Account " + accountId + " is " + status + " and takes no postings");
        this.accountId = accountId;
        this.status = status;
    }

    public AccountId accountId() {
        return accountId;
    }

    public AccountStatus status() {
        return status;
    }
}
