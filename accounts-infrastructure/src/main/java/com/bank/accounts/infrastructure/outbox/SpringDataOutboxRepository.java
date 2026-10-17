package com.bank.accounts.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Unpublished, unparked rows in insertion order, skipping every row of an aggregate that has a
     * parked row: a parked event keeps its account's later events held until it is replayed, so
     * consumers of the aggregate topic see each account's events in order (ADR-019; ADR-021
     * decision 4). Other accounts are not held. Index ix_outbox_parked_aggregate (V2).
     */
    @Query(value = """
        select * from outbox_event o
        where o.published_at is null and o.parked_at is null
          and not exists (select 1 from outbox_event p
                          where p.parked_at is not null and p.aggregate_id = o.aggregate_id)
        order by o.created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    long countByPublishedAtIsNull();

    long countByPublishedAtIsNullAndParkedAtIsNull();

    long countByParkedAtIsNotNull();

    /** Parked rows not yet counted in outbox_parked_events_total: operator parks done in SQL. */
    @Query("select e from OutboxEventJpaEntity e where e.parkedAt is not null and e.parkCounted = false")
    List<OutboxEventJpaEntity> findUncountedParks();

    @Query("select min(e.occurredAt) from OutboxEventJpaEntity e where e.publishedAt is null and e.parkedAt is null")
    Instant oldestPendingOccurredAt();
}
