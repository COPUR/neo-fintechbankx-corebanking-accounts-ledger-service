package com.bank.accounts.domain.port.in;

import com.bank.accounts.domain.Account;

public interface OpenAccountUseCase {

    /** The opened account, or the one a replay of the same request key opened. */
    Account open(OpenAccountCommand command);
}
