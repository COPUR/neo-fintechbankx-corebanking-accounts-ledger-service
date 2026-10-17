package com.bank.accounts.infrastructure.outbox;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SaslAuthenticationException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class OutboxRelayTest {

    private static final Instant NOW = Instant.parse("2026-10-07T12:00:00Z");

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final List<String> transactionLog = new java.util.ArrayList<>();
    private final TransactionTemplate transactions = inlineTransactions(transactionLog);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    /** Free unless a test says another replica holds it; acquisitions are counted. */
    private boolean lockFree = true;
    private int lockAcquisitions;
    private final RelayLock lock = () -> {
        if (!lockFree) {
            return java.util.Optional.empty();
        }
        lockAcquisitions++;
        return java.util.Optional.of(() -> { });
    };
    private final OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, lock,
        Clock.fixed(NOW, ZoneOffset.UTC), 50, Duration.ofSeconds(1), Duration.ofDays(7), meters);

    @Test
    void anotherReplicaHoldingTheLockMeansNothingIsSent() {
        lockFree = false;

        assertThat(relay.relayOnce()).isZero();
        verify(outbox, never()).findUnpublishedBatch(50);
        verify(kafka, never()).send(any(ProducerRecord.class));
    }

    @Test
    void aFailedSendStopsTheBatchSoLaterEventsCannotOvertakeIt() {
        OutboxEventJpaEntity first = row("ACC-1");
        OutboxEventJpaEntity second = row("ACC-1");
        OutboxEventJpaEntity third = row("ACC-1");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, second, third));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null))
            .thenReturn(CompletableFuture.failedFuture(new org.apache.kafka.common.errors.TimeoutException("broker down")));

        int sent = relay.relayOnce();

        assertThat(sent).isEqualTo(1);
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getParkedAt()).isNull();
        assertThat(second.getAttempts()).isZero();   // ADR-021 decision 4: nothing is marked
        assertThat(second.getLastError()).isNull();
        assertThat(third.getAttempts()).isZero();
        assertThat(failures("TimeoutException")).isEqualTo(1.0);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
    }

    /**
     * Review item d: no database transaction (and so no transaction-held
     * connection) is open while a send waits on Kafka. The batch is claimed
     * in one short transaction; each outcome is saved in its own.
     */
    @Test
    void noTransactionIsOpenWhileASendWaitsOnKafkaAndEachOutcomeIsSavedInItsOwn() {
        OutboxEventJpaEntity first = row("ACC-1");
        OutboxEventJpaEntity poison = row("ACC-2");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first, poison));
        List<Boolean> openDuringSend = new java.util.ArrayList<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            openDuringSend.add(inTransaction());
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "ACC-2".equals(record.key())
                ? CompletableFuture.failedFuture(new RecordTooLargeException("too large"))
                : CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        List<Boolean> openDuringSave = new java.util.ArrayList<>();
        when(outbox.save(any(OutboxEventJpaEntity.class))).thenAnswer(invocation -> {
            openDuringSave.add(inTransaction());
            return invocation.getArgument(0);
        });

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(openDuringSend).containsExactly(false, false);
        assertThat(openDuringSave).containsExactly(true, true);     // published mark, then the park
        assertThat(transactionLog).containsExactly("begin", "commit", "begin", "commit", "begin", "commit");
        assertThat(first.getPublishedAt()).isEqualTo(NOW);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
    }

    /** ADR-019 (owner decision 2026-10-08): one topic per aggregate, key = aggregateId, eventType/eventId/correlationId headers. */
    @Test
    void everyAccountEventGoesToTheAggregateTopicWithKeyAndHeaders() throws Exception {
        AccountEventEnvelopeFactory envelopes = new AccountEventEnvelopeFactory(new com.fasterxml.jackson.databind.ObjectMapper());
        com.bank.accounts.domain.Account account = AccountEventEnvelopeFactoryTest.accountRaisingEveryEvent();
        List<OutboxEventJpaEntity> rows = new java.util.ArrayList<>();
        account.getDomainEvents().forEach(event -> rows.add(envelopes.toOutboxRow(account, event, "corr-agg")));
        when(outbox.findUnpublishedBatch(50)).thenReturn(rows);
        when(kafka.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay.relayOnce()).isEqualTo(rows.size());

        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(rows.size())).send(sent.capture());
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (ProducerRecord<String, String> record : sent.getAllValues()) {
            com.fasterxml.jackson.databind.JsonNode envelope = json.readTree(record.value());
            assertThat(record.topic()).as("one topic per aggregate (ADR-019)").isEqualTo("evt.acc.account.v1");
            assertThat(record.key()).isEqualTo(envelope.get("aggregateId").asText()).isEqualTo("ACC-ENV");
            assertThat(header(record, "eventType")).isEqualTo(envelope.get("eventType").asText());
            assertThat(header(record, "eventId")).isEqualTo(envelope.get("eventId").asText());
            assertThat(header(record, "correlationId")).isEqualTo(envelope.get("correlationId").asText()).isEqualTo("corr-agg");
        }
        assertThat(sent.getAllValues()).extracting(r -> header(r, "eventType")).containsExactly(
            "CoreBanking.Account.Opened.v1", "CoreBanking.Account.Credited.v1", "CoreBanking.Account.Debited.v1",
            "CoreBanking.Account.Frozen.v1", "CoreBanking.Account.Unfrozen.v1", "CoreBanking.Account.Closed.v1");
    }

    @Test
    void recordIsKeyedByAggregateAndCarriesTracingHeaders() {
        OutboxEventJpaEntity row = row("ACC-9");

        ProducerRecord<String, String> record = OutboxRelay.toRecord(row);

        assertThat(record.topic()).isEqualTo("evt.acc.account.v1");
        assertThat(record.key()).isEqualTo("ACC-9");
        assertThat(record.value()).isEqualTo("{}");
        assertThat(header(record, "eventType")).isEqualTo("CoreBanking.Account.Opened.v1");
        assertThat(header(record, "eventId")).isEqualTo(row.getEventId().toString());
        assertThat(header(record, "correlationId")).isEqualTo("corr-9");
        assertThat(header(record, "x-fapi-interaction-id")).isEqualTo("corr-9");
    }

    /**
     * ADR-021 decision 4 (adr-runbooks #10 e6dd76a): every error that is not
     * a payload error (retryable, authorization, unclassified) never parks a
     * row however long it lasts: the batch stops, nothing is marked, the
     * relay backs off, and outbox.send.failures counts it by exception class.
     */
    @ParameterizedTest
    @MethodSource("nonPayloadErrors")
    void nonPayloadErrorsNeverParkOrMarkARowHoweverLongTheyLast(Exception failure, String exceptionClass) {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, lock, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("ACC-R");
        OutboxEventJpaEntity later = row("ACC-S");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> CompletableFuture.failedFuture(failure));

        for (int run = 0; run < 40; run++) {
            assertThat(relay.relayOnce()).isZero();
            clock.advance(Duration.ofHours(3));               // 40 runs over five days, always past the backoff
        }

        assertThat(head.getParkedAt()).isNull();
        assertThat(head.getAttempts()).isZero();
        assertThat(head.getLastError()).isNull();
        assertThat(later.getAttempts()).isZero();              // order kept: nothing overtook the head
        assertThat(failures(exceptionClass)).isEqualTo(40.0);
        assertThat(meters.find("outbox.parked.events").counters()).isEmpty();
        verify(kafka, times(40)).send(any(ProducerRecord.class));
    }

    static Stream<Arguments> nonPayloadErrors() {
        return Stream.of(
            Arguments.of(new org.apache.kafka.common.errors.TimeoutException("Expiring 1 record(s)"), "TimeoutException"),
            Arguments.of(new NotEnoughReplicasException("fewer in-sync replicas than required"), "NotEnoughReplicasException"),
            Arguments.of(new NetworkException("disconnected"), "NetworkException"),
            Arguments.of(new KafkaProducerException(null, "send failed", new NotLeaderOrFollowerException("leader moved")),
                "NotLeaderOrFollowerException"),
            Arguments.of(new TopicAuthorizationException(Set.of("evt.acc.account.v1")), "TopicAuthorizationException"),
            Arguments.of(new SaslAuthenticationException("Access denied"), "SaslAuthenticationException"),
            Arguments.of(new KafkaException("Failed to construct kafka producer"), "KafkaException"),
            Arguments.of(new IllegalStateException("something nobody classified"), "IllegalStateException"));
    }

    @Test
    void theRelaysOwnSendTimeoutStopsTheBatchWithoutMarkingTheRow() {
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, lock, new MutableClock(NOW), 50,
            Duration.ofMillis(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("ACC-W");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head));
        when(kafka.send(any(ProducerRecord.class))).thenReturn(new CompletableFuture<>());

        relay.relayOnce();

        assertThat(head.getAttempts()).isZero();
        assertThat(head.getParkedAt()).isNull();
        assertThat(failures("TimeoutException")).isEqualTo(1.0);
    }

    /** After a non-payload failure the relay backs off (1 s doubling to 5 min) and resumes once a run gets through. */
    @Test
    void theRelayBacksOffAfterAFailureAndResumesWhenTheCauseIsFixed() {
        MutableClock clock = new MutableClock(NOW);
        OutboxRelay relay = new OutboxRelay(outbox, kafka, transactions, lock, clock, 50,
            Duration.ofSeconds(1), Duration.ofDays(7), meters);
        OutboxEventJpaEntity head = row("ACC-A");
        OutboxEventJpaEntity later = row("ACC-B");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(head, later));
        when(kafka.send(any(ProducerRecord.class)))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(new TopicAuthorizationException(Set.of("t"))))
            .thenAnswer(invocation -> CompletableFuture.failedFuture(new TopicAuthorizationException(Set.of("t"))))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isZero();
        assertThat(relay.relayOnce()).isZero();                 // within the 1 s backoff: nothing is tried
        verify(kafka, times(1)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isZero();                 // second failure: backoff doubles to 2 s
        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isZero();
        verify(kafka, times(2)).send(any(ProducerRecord.class));

        clock.advance(Duration.ofSeconds(1));
        assertThat(relay.relayOnce()).isEqualTo(2);             // cause fixed: both rows go
        clock.advance(Duration.ofMillis(1));
        assertThat(relay.relayOnce()).isEqualTo(2);             // no backoff left after a run got through
        assertThat(head.getParkedAt()).isNull();
    }

    /**
     * ADR-021 decision 4: payload errors (record too large, serialization,
     * invalid topic) can never be sent as they are: the row is parked at once
     * with its reason and the batch continues.
     */
    @ParameterizedTest
    @MethodSource("payloadErrors")
    void payloadErrorsParkTheRowAtOnceAndTheBatchContinues(Exception failure, String reason) {
        OutboxEventJpaEntity poison = row("ACC-P");
        OutboxEventJpaEntity later = row("ACC-Q");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, later));
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            return "ACC-P".equals(record.key())
                ? CompletableFuture.failedFuture(failure)
                : CompletableFuture.completedFuture((SendResult<String, String>) null);
        });

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(poison.getPublishedAt()).isNull();
        assertThat(poison.getLastError()).isEqualTo(reason);
        assertThat(poison.getParkReason()).isEqualTo("payload error: " + reason);
        assertThat(later.getPublishedAt()).isEqualTo(NOW);
        // Kafka guide 5f7d546: outbox.parked.events{exception=<simple class name>} counts every park (alert on increase).
        String exceptionClass = reason.substring(0, reason.indexOf(':'));
        assertThat(meters.find("outbox.parked.events").tag("exception", exceptionClass).counter()).isNotNull();
        assertThat(meters.get("outbox.parked.events").tag("exception", exceptionClass).counter().count()).isEqualTo(1.0);
        assertThat(failures(exceptionClass)).isZero();
        assertThat(poison.isParkCounted()).isTrue();          // the relay counted it already
    }

    static Stream<Arguments> payloadErrors() {
        return Stream.of(
            Arguments.of(new RecordTooLargeException("The message is 2000000 bytes"),
                "RecordTooLargeException: The message is 2000000 bytes"),
            Arguments.of(new SerializationException("Can't convert value"),
                "SerializationException: Can't convert value"),
            Arguments.of(new KafkaProducerException(null, "send failed", new InvalidTopicException("bad name")),
                "InvalidTopicException: bad name"));
    }

    /**
     * ADR-019: consumers rely on per-aggregate order (a balance replay expects Debited and
     * Credited in booking order). A row parked on a payload error keeps the rest of its account blocked: the
     * later row of the same account is not sent in this run (nor marked), other accounts still go.
     */
    @Test
    void aParkedRowKeepsTheRestOfItsAggregateBlocked() {
        OutboxEventJpaEntity poison = row("ACC-1", "CoreBanking.Account.Debited.v1");
        OutboxEventJpaEntity sameAccount = row("ACC-1", "CoreBanking.Account.Credited.v1");
        OutboxEventJpaEntity otherAccount = row("ACC-2", "CoreBanking.Account.Opened.v1");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(poison, sameAccount, otherAccount));
        when(kafka.send(any(ProducerRecord.class)))
            .thenReturn(CompletableFuture.failedFuture(new RecordTooLargeException("too large")))
            .thenReturn(CompletableFuture.completedFuture((SendResult<String, String>) null));

        assertThat(relay.relayOnce()).isEqualTo(1);

        assertThat(poison.getParkedAt()).isEqualTo(NOW);
        assertThat(sameAccount.getPublishedAt()).isNull();
        assertThat(sameAccount.getParkedAt()).isNull();
        assertThat(sameAccount.getAttempts()).isZero();
        assertThat(sameAccount.getLastError()).isNull();
        assertThat(otherAccount.getPublishedAt()).isEqualTo(NOW);
        org.mockito.ArgumentCaptor<ProducerRecord<String, String>> sent = org.mockito.ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka, times(2)).send(sent.capture());
        assertThat(sent.getAllValues()).extracting(r -> header(r, "eventType"))
            .containsExactly("CoreBanking.Account.Debited.v1", "CoreBanking.Account.Opened.v1");
        assertThat(failures("RecordTooLargeException")).isZero();
    }

    /** Mandates 9b9374c: an operator park (runbook SQL) is counted once, exception="OperatorPark". */
    @Test
    void anOperatorParkIsCountedOnceByTheRelay() {
        OutboxEventJpaEntity operatorParked = row("ACC-O");
        // What the runbook's operator SQL leaves behind: parked with a reason, not yet counted.
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkedAt", NOW);
        org.springframework.test.util.ReflectionTestUtils.setField(operatorParked, "parkReason", "operator: INC-1 ACL");
        when(outbox.findUncountedParks()).thenReturn(List.of(operatorParked)).thenReturn(List.of());
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of());

        relay.relayOnce();
        relay.relayOnce();

        assertThat(operatorParked.isParkCounted()).isTrue();
        assertThat(meters.get("outbox.parked.events").tag("exception", "OperatorPark").counter().count()).isEqualTo(1.0);
    }

    @Test
    void parksAreNotCountedByAReplicaWithoutTheRelayLock() {
        lockFree = false;

        relay.relayOnce();

        verify(outbox, never()).findUncountedParks();
        assertThat(meters.find("outbox.parked.events").counters()).isEmpty();
    }

    @Test
    void interruptedSendStopsTheBatch() {
        OutboxEventJpaEntity first = row("ACC-I");
        when(outbox.findUnpublishedBatch(50)).thenReturn(List.of(first));
        CompletableFuture<SendResult<String, String>> never = new CompletableFuture<>();
        when(kafka.send(any(ProducerRecord.class))).thenReturn(never);
        Thread.currentThread().interrupt();

        assertThat(relay.relayOnce()).isZero();
        assertThat(Thread.interrupted()).isTrue();
        assertThat(first.getAttempts()).isZero();
        assertThat(first.getParkedAt()).isNull();
    }

    @Test
    void traceparentAndInteractionIdAreSentOnlyWhenTheRowHasThem() {
        OutboxEventJpaEntity traced = row("ACC-T");
        traced.recordTraceparent("00-11111111111111111111111111111111-2222222222222222-01");
        OutboxEventJpaEntity untraced = new OutboxEventJpaEntity(UUID.randomUUID(), "Account", "ACC-U", 0L,
            "CoreBanking.Account.Opened.v1", "{}", " ", NOW);

        assertThat(header(OutboxRelay.toRecord(traced), "traceparent"))
            .isEqualTo("00-11111111111111111111111111111111-2222222222222222-01");
        ProducerRecord<String, String> bare = OutboxRelay.toRecord(untraced);
        assertThat(bare.headers().lastHeader("traceparent")).isNull();
        assertThat(bare.headers().lastHeader("x-fapi-interaction-id")).isNull();
        assertThat(bare.headers().lastHeader("eventId")).isNotNull();
    }

    @Test
    void purgeDeletesRowsPublishedBeforeTheRetentionWindow() {
        when(outbox.deletePublishedBefore(NOW.minus(Duration.ofDays(7)))).thenReturn(3);

        assertThat(relay.purgePublished()).isEqualTo(3);
    }

    private double failures(String exceptionClass) {
        return meters.find("outbox.send.failures").tag("exception", exceptionClass).counters().stream()
            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    private static OutboxEventJpaEntity row(String aggregateId) {
        return row(aggregateId, "CoreBanking.Account.Opened.v1");
    }

    private static OutboxEventJpaEntity row(String aggregateId, String eventType) {
        return new OutboxEventJpaEntity(UUID.randomUUID(), "Account", aggregateId, 0L,
            eventType, "{}", "corr-9", NOW);
    }

    /** A clock the test moves forward (the relay takes the injected Clock). */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Runs the callback inline and records "begin"/"commit" around it, so tests can see what ran inside. */
    private static TransactionTemplate inlineTransactions(List<String> log) {
        return new TransactionTemplate() {
            @Override
            public <T> T execute(TransactionCallback<T> action) {
                log.add("begin");
                try {
                    return action.doInTransaction(new SimpleTransactionStatus());
                } finally {
                    log.add("commit");
                }
            }
        };
    }

    private boolean inTransaction() {
        return transactionLog.lastIndexOf("begin") > transactionLog.lastIndexOf("commit");
    }
}
