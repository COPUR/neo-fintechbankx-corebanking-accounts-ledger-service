package com.bank.accounts.infrastructure.persistence;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.port.out.AccountRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Account out-port on Postgres. Writes join the use case's transaction
 * (MANDATORY) and flush at once, so the version the outbox envelope carries
 * is the stored one.
 */
@Component
public class JpaAccountRepositoryAdapter implements AccountRepository {

    private final SpringDataAccountRepository rows;

    public JpaAccountRepositoryAdapter(SpringDataAccountRepository rows) {
        this.rows = rows;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findById(AccountId id) {
        return rows.findById(id.value()).map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Account> lockForPosting(Collection<AccountId> ids) {
        List<String> keys = ids.stream().map(AccountId::value).distinct().sorted().toList();
        return rows.lockAllInIdOrder(keys).stream().map(LedgerPersistenceMapper::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Account> findByOpenRequestKey(String requestKey) {
        return rows.findByOpenRequestKey(requestKey).map(LedgerPersistenceMapper::toDomain);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Account save(Account account, String openRequestKey) {
        AccountJpaEntity saved = rows.saveAndFlush(LedgerPersistenceMapper.newRow(account, openRequestKey));
        account.setVersion(saved.getVersion());
        return account;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Account save(Account account) {
        AccountJpaEntity row = rows.findById(account.getId().value())
            .orElseThrow(() -> new IllegalStateException("Account " + account.getId() + " is not stored"));
        if (!Objects.equals(row.getVersion(), account.getVersion())) {
            throw new org.springframework.orm.ObjectOptimisticLockingFailureException(AccountJpaEntity.class,
                account.getId().value());
        }
        LedgerPersistenceMapper.copyState(account, row);
        rows.saveAndFlush(row);
        account.setVersion(row.getVersion());
        return account;
    }
}
