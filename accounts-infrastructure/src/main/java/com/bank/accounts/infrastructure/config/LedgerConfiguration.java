package com.bank.accounts.infrastructure.config;

import com.bank.accounts.application.AccountsLedgerService;
import com.bank.accounts.domain.port.out.AccountEventPort;
import com.bank.accounts.domain.port.out.AccountRepository;
import com.bank.accounts.domain.port.out.JournalEntryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;

/** Wires the use cases to their adapters; the application module has no Spring annotations of its own. */
@Configuration
public class LedgerConfiguration {

    @Bean
    AccountsLedgerService accountsLedgerService(AccountRepository accounts, JournalEntryRepository entries,
                                                AccountEventPort events, PlatformTransactionManager transactions,
                                                Clock clock) {
        return new AccountsLedgerService(accounts, entries, events, new TransactionTemplate(transactions), clock);
    }
}
