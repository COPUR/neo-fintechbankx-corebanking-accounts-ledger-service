package com.bank.accounts.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.SerializationException;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Relays committed outbox rows to Kafka in insertion order, all to the
 * Account aggregate topic {@link #TOPIC} (ADR-019).
 *
 * One replica relays at a time ({@link RelayLock}: a session-level Postgres
 * advisory lock held for the whole run), so the service can scale out without
 * reordering an aggregate's events. No database transaction is open while a
 * send waits on Kafka: the batch is claimed in a short transaction and each
 * outcome (published, or parked on a payload error) is saved in its own. A
 * row whose send succeeded but whose published mark did not commit is sent
 * again next run; consumers de-duplicate on eventId, which makes the
 * at-least-once delivery safe.
 *
 * A failed send is handled per ADR-021 decision 4 (adr-runbooks #10 e6dd76a):
 * <ul>
 *   <li>payload errors (RecordTooLarge, Serialization, InvalidTopic): the row
 *   can never be sent as it is; it is parked at once (parked_at, park_reason,
 *   last_error), counted in outbox.parked.events tagged with the exception's
 *   simple class name (alert on any increase), and the batch continues with
 *   other accounts. The parked row holds back the later rows of its account
 *   (in this run, and in later runs through
 *   {@link SpringDataOutboxRepository#findUnpublishedBatch}) until an operator
 *   replays it, so consumers of the aggregate topic never see an account's
 *   events out of order (ADR-019; a consumer replaying balances needs Debited
 *   and Credited in booking order). An operator park holds its account the same
 *   way;</li>
 *   <li>every other error (retryable broker or network errors, the relay's own
 *   send timeout, authorization, anything unclassified) never parks a row,
 *   however long it lasts: the batch stops, nothing is marked, the relay backs
 *   off (1 s doubling to 5 min) and counts the failure in outbox.send.failures
 *   tagged with the exception's simple class name (never an id).</li>
 * </ul>
 * Only an operator parks such a row, by hand and with a recorded park_reason
 * (runbook section 7); the relay counts each such park once, under its lock,
 * as outbox.parked.events{exception="OperatorPark"} (park_counted, V2). The outage alert is outbox_oldest_pending_age_seconds.
 */
public class OutboxRelay {

    public static final long RELAY_LOCK_KEY = 0x6163635F6F7574L; // "acc_out"
    /**
     * The Account aggregate topic (ADR-019, one topic per aggregate): every account event
     * goes here, keyed by the account id, and the eventType record header names the event.
     * Computed here rather than stored per row (V2).
     */
    public static final String TOPIC = "evt.acc.account.v1";
    static final String SEND_FAILURES = "outbox.send.failures";
    static final String PARKED_EVENTS = "outbox.parked.events";
    static final String OPERATOR_PARK = "OperatorPark";
    static final Duration BACKOFF_START = Duration.ofSeconds(1);
    static final Duration BACKOFF_MAX = Duration.ofMinutes(5);
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final SpringDataOutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate transactions;
    private final RelayLock relayLock;
    private final Clock clock;
    private final int batchSize;
    private final Duration sendTimeout;
    private final Duration retention;
    private final MeterRegistry meters;
    // Only the scheduler thread runs the relay.
    private Instant backoffUntil;
    private int failureStreak;

    public OutboxRelay(SpringDataOutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       TransactionTemplate transactions, RelayLock relayLock, Clock clock, int batchSize,
                       Duration sendTimeout, Duration retention, MeterRegistry meters) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.transactions = transactions;
        this.relayLock = relayLock;
        this.clock = clock;
        this.batchSize = batchSize;
        this.sendTimeout = sendTimeout;
        this.retention = retention;
        this.meters = meters;
    }

    /**
     * One run: under the relay lock, claim the batch (and count operator
     * parks) in a short transaction, send each row with no transaction open,
     * and save each outcome in its own short transaction.
     *
     * @return number of events published in this run
     */
    public int relayOnce() {
        if (backoffUntil != null && clock.instant().isBefore(backoffUntil)) {
            return 0;
        }
        Optional<RelayLock.Held> lock = relayLock.tryAcquire();
        if (lock.isEmpty()) {
            return 0;
        }
        try (RelayLock.Held held = lock.get()) {
            List<OutboxEventJpaEntity> batch = transactions.execute(status -> {
                countOperatorParks();
                return outbox.findUnpublishedBatch(batchSize);
            });
            return batch == null ? 0 : send(batch);
        }
    }

    /** Sends outside any transaction; the rows are detached, each outcome is saved back on its own. */
    private int send(List<OutboxEventJpaEntity> batch) {
        Set<String> blockedAggregates = new HashSet<>();
        int sent = 0;
        for (OutboxEventJpaEntity row : batch) {
            if (blockedAggregates.contains(row.getAggregateId())) {
                continue;
            }
            try {
                kafka.send(toRecord(row)).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return sent;
            } catch (Exception e) {
                if (isPayloadError(e)) {
                    String reason = describe(e);
                    Instant parkedAt = clock.instant();
                    save(row, () -> {
                        row.markFailed(reason);
                        row.park(parkedAt, "payload error: " + reason);
                    });
                    recordParked(unwrap(e).getClass().getSimpleName());
                    blockedAggregates.add(row.getAggregateId());
                    log.error("Outbox relay parked event {} ({}): payload error {}; its account's later events wait"
                        + " until it is replayed, other accounts continue (ADR-021 decision 4)",
                        row.getEventId(), row.getEventType(), reason);
                    continue;
                }
                backOff(e);
                return sent;
            }
            Instant publishedAt = clock.instant();
            save(row, () -> row.markPublished(publishedAt));
            sent++;
        }
        failureStreak = 0;
        backoffUntil = null;
        return sent;
    }

    private void save(OutboxEventJpaEntity row, Runnable change) {
        transactions.executeWithoutResult(status -> {
            change.run();
            outbox.save(row);
        });
    }

    /** ADR-021 decision 4 payload errors: the record itself can never be sent. */
    static boolean isPayloadError(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RecordTooLargeException || cause instanceof SerializationException
                    || cause instanceof InvalidTopicException) {
                return true;
            }
        }
        return false;
    }

    /** Counts a failed send, tagged with the unwrapped exception's simple class name only (never ids). */
    public void recordSendFailure(Throwable failure) {
        meters.counter(SEND_FAILURES, "exception", unwrap(failure).getClass().getSimpleName()).increment();
    }

    /** Counts a parked row (outbox_parked_events_total); alert on any increase. */
    public void recordParked(String exceptionClass) {
        meters.counter(PARKED_EVENTS, "exception", exceptionClass).increment();
    }

    /**
     * Operator parks happen in SQL (runbook section 7); count each once with
     * exception="OperatorPark". Runs only while this replica holds the relay
     * lock, so one replica counts.
     */
    private void countOperatorParks() {
        for (OutboxEventJpaEntity parked : outbox.findUncountedParks()) {
            parked.markParkCounted();
            recordParked(OPERATOR_PARK);
            log.warn("Outbox event {} ({}) was parked by an operator", parked.getEventId(), parked.getEventType());
        }
    }

    /** Not the row's fault: count it by class, mark nothing, back off (1 s doubling to 5 min). */
    private void backOff(Exception e) {
        Throwable cause = unwrap(e);
        recordSendFailure(e);
        failureStreak++;
        Duration backoff = BACKOFF_START.multipliedBy(1L << Math.min(failureStreak - 1, 20));
        if (backoff.compareTo(BACKOFF_MAX) > 0) {
            backoff = BACKOFF_MAX;
        }
        backoffUntil = clock.instant().plus(backoff);
        log.warn("Outbox relay send failed ({}, retryable={}); nothing marked, retrying in {} (ADR-021 decision 4)",
            cause.getClass().getSimpleName(), isRetryable(e), backoff, e);
    }

    /** Retryable: a Kafka RetriableException anywhere in the cause chain, or the relay's own send timeout. */
    static boolean isRetryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof RetriableException || cause instanceof TimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** The underlying failure, without the future and KafkaTemplate wrappers, for last_error. */
    static String describe(Throwable failure) {
        Throwable cause = unwrap(failure);
        return cause.getMessage() == null
            ? cause.getClass().getSimpleName()
            : cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof ExecutionException || cause instanceof CompletionException
                || cause instanceof KafkaProducerException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    public int purgePublished() {
        Integer deleted = transactions.execute(status -> outbox.deletePublishedBefore(clock.instant().minus(retention)));
        return deleted == null ? 0 : deleted;
    }

    /**
     * The record for a row: aggregate topic, key = aggregateId (the envelope's aggregateId, UTF-8
     * text through the String serializer), and the ADR-019 section 3 headers as UTF-8 text.
     */
    static ProducerRecord<String, String> toRecord(OutboxEventJpaEntity row) {
        ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, row.getAggregateId(), row.getPayload());
        record.headers().add("eventType", row.getEventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", row.getEventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add("correlationId", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        if (row.getCorrelationId() != null && !row.getCorrelationId().isBlank()) {
            record.headers().add("x-fapi-interaction-id", row.getCorrelationId().getBytes(StandardCharsets.UTF_8));
        }
        if (row.getTraceparent() != null) {
            record.headers().add("traceparent", row.getTraceparent().getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
