package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;

public interface FindAccountUseCase {

    /**
     * The account, if the caller may see it. An account held by another
     * customer is reported exactly like an unknown one (AccountNotFoundException).
     */
    Account find(AccountId accountId, Caller caller);
}
