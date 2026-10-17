package com.bank.accounts.domain.port.out;

import com.bank.accounts.domain.Account;
import com.bank.shared.kernel.domain.DomainEvent;

import java.util.List;

/** Publishes an account's events in the caller's transaction (transactional outbox). */
public interface AccountEventPort {

    void publish(Account account, List<DomainEvent> events);
}
