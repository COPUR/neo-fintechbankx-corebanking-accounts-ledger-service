package com.bank.accounts.domain.port.out;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface AccountRepository {

    Optional<Account> findById(AccountId id);

    /**
     * Reads and locks the accounts for update, always in ascending id order so
     * two postings that share accounts cannot deadlock. Unknown ids are left out.
     */
    List<Account> lockForPosting(Collection<AccountId> ids);

    /** The account a previous open request with this key created. */
    Optional<Account> findByOpenRequestKey(String requestKey);

    /** The request key the account was opened with, used to compare a replay. */
    Account save(Account account, String openRequestKey);

    Account save(Account account);
}
