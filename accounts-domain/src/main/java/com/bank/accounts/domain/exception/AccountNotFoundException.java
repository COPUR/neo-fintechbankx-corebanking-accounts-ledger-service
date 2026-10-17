package com.bank.accounts.domain.exception;

import com.bank.accounts.domain.AccountId;

/** No account has this id (or the caller may not see it). */
public class AccountNotFoundException extends RuntimeException {

    private final AccountId accountId;

    public AccountNotFoundException(AccountId accountId) {
        super("Account not found: " + accountId);
        this.accountId = accountId;
    }

    public AccountId accountId() {
        return accountId;
    }
}
