package com.bank.accounts.application;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.port.out.AccountEventPort;
import com.bank.accounts.domain.port.out.AccountRepository;
import com.bank.accounts.domain.port.out.JournalEntryRepository;
import com.bank.shared.kernel.domain.DomainEvent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test doubles for the out-ports; they record what the service did. */
final class InMemoryStores implements AccountRepository, JournalEntryRepository, AccountEventPort {

    final Map<AccountId, Account> accounts = new LinkedHashMap<>();
    final Map<String, AccountId> openKeys = new HashMap<>();
    final Map<JournalEntryId, JournalEntry> entries = new LinkedHashMap<>();
    final List<DomainEvent> published = new ArrayList<>();
    final List<List<AccountId>> lockOrders = new ArrayList<>();

    @Override
    public Optional<Account> findById(AccountId id) {
        return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public List<Account> lockForPosting(Collection<AccountId> ids) {
        List<Account> locked = ids.stream().distinct().sorted(Comparator.comparing(AccountId::value))
            .map(accounts::get).filter(java.util.Objects::nonNull).toList();
        lockOrders.add(locked.stream().map(Account::getId).toList());
        return locked;
    }

    @Override
    public Optional<Account> findByOpenRequestKey(String requestKey) {
        return Optional.ofNullable(openKeys.get(requestKey)).map(accounts::get);
    }

    @Override
    public Account save(Account account, String openRequestKey) {
        openKeys.put(openRequestKey, account.getId());
        return save(account);
    }

    @Override
    public Account save(Account account) {
        accounts.put(account.getId(), account);
        return account;
    }

    @Override
    public Optional<JournalEntry> findById(JournalEntryId id) {
        return Optional.ofNullable(entries.get(id));
    }

    @Override
    public Optional<JournalEntry> findByReference(String reference) {
        return entries.values().stream().filter(entry -> entry.reference().equals(reference)).findFirst();
    }

    @Override
    public JournalEntry save(JournalEntry entry) {
        entries.put(entry.getId(), entry);
        return entry;
    }

    @Override
    public void publish(Account account, List<DomainEvent> events) {
        published.addAll(events);
    }
}
