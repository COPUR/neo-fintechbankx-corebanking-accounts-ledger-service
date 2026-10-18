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

    /**
     * The relay's mark after a send, in one statement: an unparked row is marked published; a row
     * an operator parked during the send gets sent_while_parked_at instead (V6), so the database
     * refuses discarding an event Kafka has, and stays parked. Returns 1 either way (0 only if the
     * row was published or discarded meanwhile); {@link #isUnpublished}, in the same transaction, tells which. Never a
     * merge of the whole row read before the send.
     */
    @Modifying
    @Query(value = """
        update outbox_event set
            published_at = case when parked_at is null then cast(:at as timestamptz) end,
            sent_while_parked_at = case when parked_at is not null
                then coalesce(sent_while_parked_at, cast(:at as timestamptz)) else sent_while_parked_at end,
            attempts = attempts + 1,
            last_error = case when parked_at is not null
                then 'sent while parked: delivered to Kafka; replay it, do not discard it' end
        where event_id = :id and published_at is null
        """, nativeQuery = true)
    int markSent(@Param("id") UUID eventId, @Param("at") Instant at);

    @Query(value = "select published_at is null from outbox_event where event_id = :id", nativeQuery = true)
    Boolean isUnpublished(@Param("id") UUID eventId);

    /** A payload error parks the row, unless an operator parked it first (0). */
    @Modifying
    @Query(value = """
        update outbox_event
        set attempts = attempts + 1, last_error = :error, parked_at = :at, park_reason = :reason, park_counted = true
        where event_id = :id and published_at is null and parked_at is null
        """, nativeQuery = true)
    int markParked(@Param("id") UUID eventId, @Param("at") Instant at, @Param("error") String error,
                   @Param("reason") String reason);

    /** When the oldest discard request still waiting for the relay was made (null if none). */
    @Query(value = """
        select min(discard_requested_at) from outbox_event
        where discard_requested_at is not null and published_at is null
        """, nativeQuery = true)
    Instant oldestPendingDiscardRequestedAt();

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

    /**
     * Discards every row an operator asked to discard (V4, docs/RUNBOOK.md Outbox) by writing its
     * audit row. Only the event id is given: trg_outbox_discard_audit_derive locks the outbox row
     * and fills every other column from it (requester and times included), and
     * trg_outbox_discard_audit_mark then marks the row published and no longer parked, which
     * releases the account's later rows. Returns the number of rows discarded. The relay runs it
     * under its lock. A request an operator is changing right now (row locked) is skipped until a
     * later run, so an open operator transaction never stalls the relay.
     */
    @Modifying
    @Query(value = """
        insert into outbox_discard_audit (event_id)
        select o.event_id
        from outbox_event o
        where o.discard_requested_at is not null and o.parked_at is not null and o.published_at is null
          and o.sent_while_parked_at is null
        order by o.created_seq
        for update of o skip locked
        on conflict (event_id) do nothing
        """, nativeQuery = true)
    int auditDiscardRequests();
}
