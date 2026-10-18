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
     * The relay's published mark, after the send: only while the row is still unparked. An
     * operator may park the row while the relay is sending it; then nothing is marked (0) and the
     * relay notes it on the row instead. Never a merge of the whole row read before the send.
     */
    @Modifying
    @Query(value = """
        update outbox_event set published_at = :at, attempts = attempts + 1, last_error = null
        where event_id = :id and published_at is null and parked_at is null
        """, nativeQuery = true)
    int markPublished(@Param("id") UUID eventId, @Param("at") Instant at);

    /** A payload error parks the row, unless an operator parked it first (0). */
    @Modifying
    @Query(value = """
        update outbox_event
        set attempts = attempts + 1, last_error = :error, parked_at = :at, park_reason = :reason, park_counted = true
        where event_id = :id and published_at is null and parked_at is null
        """, nativeQuery = true)
    int markParked(@Param("id") UUID eventId, @Param("at") Instant at, @Param("error") String error,
                   @Param("reason") String reason);

    /**
     * Records on a row an operator parked during its send that Kafka has it ("sent while parked"):
     * the database then refuses discarding it (V5), and the runbook replays it instead.
     */
    @Modifying
    @Query(value = """
        update outbox_event set attempts = attempts + 1, last_error = :note
        where event_id = :id and published_at is null
        """, nativeQuery = true)
    int noteSentWhileParked(@Param("id") UUID eventId, @Param("note") String note);

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
          and (o.last_error is null or o.last_error not like 'sent while parked%')
        order by o.created_seq
        for update of o skip locked
        on conflict (event_id) do nothing
        """, nativeQuery = true)
    int auditDiscardRequests();
}
