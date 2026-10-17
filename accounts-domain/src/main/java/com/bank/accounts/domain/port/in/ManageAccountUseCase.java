package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;

/** Staff operations on an account's state. */
public interface ManageAccountUseCase {

    Account freeze(AccountId accountId, String reason);

    Account unfreeze(AccountId accountId);

    Account close(AccountId accountId);
}
