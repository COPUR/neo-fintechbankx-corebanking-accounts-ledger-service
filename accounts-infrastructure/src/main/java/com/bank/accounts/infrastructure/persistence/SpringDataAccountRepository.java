package com.bank.accounts.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataAccountRepository extends JpaRepository<AccountJpaEntity, String> {

    /** SELECT ... ORDER BY account_id FOR UPDATE: rows are locked in id order, so postings cannot deadlock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AccountJpaEntity a where a.accountId in :ids order by a.accountId")
    List<AccountJpaEntity> lockAllInIdOrder(@Param("ids") Collection<String> ids);

    Optional<AccountJpaEntity> findByOpenRequestKey(String openRequestKey);
}
