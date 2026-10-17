package com.bank.accounts.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * Row of sc_acc_accounts_ledger.outbox_event: one envelope waiting to be
 * relayed to Kafka. Written in the aggregate's transaction. The row stores no
 * topic: the relay sends every row to the aggregate topic, so the table has no topic column.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventJpaEntity {

    @Id
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "aggregate_type", nullable = false, length = 64, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64, updatable = false)
    private String aggregateId;

    @Column(name = "aggregate_version", nullable = false, updatable = false)
    private long aggregateVersion;

    @Column(name = "event_type", nullable = false, length = 128, updatable = false)
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "correlation_id", nullable = false, length = 128, updatable = false)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 512)
    private String lastError;

    @Column(name = "parked_at")
    private Instant parkedAt;

    /** Why the row was parked: "payload error: ..." from the relay, or the operator's reason (V2). */
    @Column(name = "park_reason", length = 512)
    private String parkReason;

    /** True once this park was counted in outbox_parked_events_total (V2). */
    @Column(name = "park_counted", nullable = false)
    private boolean parkCounted;

    @Column(name = "traceparent", length = 55, updatable = false)
    private String traceparent;

    protected OutboxEventJpaEntity() {
    }

    public OutboxEventJpaEntity(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
                                String eventType, String payload, String correlationId,
                                Instant occurredAt) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.aggregateVersion = aggregateVersion;
        this.eventType = eventType;
        this.payload = payload;
        this.correlationId = correlationId;
        this.occurredAt = occurredAt;
    }

    public UUID getEventId() { return eventId; }
    public String getAggregateType() { return aggregateType; }
    public String getAggregateId() { return aggregateId; }
    public long getAggregateVersion() { return aggregateVersion; }
    public String getEventType() { return eventType; }
    public String getPayload() { return payload; }
    public String getCorrelationId() { return correlationId; }
    public Instant getOccurredAt() { return occurredAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getParkedAt() { return parkedAt; }
    public String getParkReason() { return parkReason; }
    public boolean isParkCounted() { return parkCounted; }
    public String getTraceparent() { return traceparent; }

    /** W3C trace context of the request that produced the event, if it had one. */
    void recordTraceparent(String value) {
        this.traceparent = value;
    }

    /**
     * Takes the row out of the relay, and holds back the later rows of its aggregate, until an operator
     * replays it; only payload errors do this automatically (ADR-021 decision 4).
     */
    void park(Instant at, String reason) {
        this.parkedAt = at;
        this.parkReason = reason == null ? null : reason.substring(0, Math.min(reason.length(), 512));
        this.parkCounted = true;
    }

    /** An operator park (runbook SQL) has been counted. */
    void markParkCounted() {
        this.parkCounted = true;
    }

    void markPublished(Instant at) {
        this.publishedAt = at;
        this.attempts++;
        this.lastError = null;
    }

    void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 512));
    }
}
