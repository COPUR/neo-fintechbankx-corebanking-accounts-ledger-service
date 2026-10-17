package com.bank.accounts.infrastructure.config;

import com.bank.accounts.infrastructure.outbox.AccountEventEnvelopeFactory;
import com.bank.accounts.infrastructure.outbox.OutboxRelay;
import com.bank.accounts.infrastructure.outbox.PostgresSessionRelayLock;
import com.bank.accounts.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Configuration
public class OutboxConfiguration {

    @Bean
    AccountEventEnvelopeFactory accountEventEnvelopeFactory(ObjectMapper objectMapper) {
        return new AccountEventEnvelopeFactory(objectMapper);
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * Outbox health gauges, exported as outbox_pending_events, outbox_parked_rows and
     * outbox_oldest_pending_age_seconds (the outage alert OutboxRelayStalled: under
     * ADR-021 decision 4 only payload errors park a row, so any other send failure
     * stops the relay and this age grows until it is fixed). The relay's own
     * counters are outbox_send_failures_total{exception} and
     * outbox_parked_events_total{exception} (Kafka guide 5f7d546).
     * Registered only where the relay runs: with it off nothing publishes, the age
     * would climb from the first write and page the squad for a relay that is off
     * on purpose (docs/RUNBOOK.md, Outbox).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "accounts.outbox.relay.enabled", havingValue = "true")
    static class RelayMetricsConfiguration {

        @Bean
        OutboxMetrics outboxMetrics(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
            return OutboxMetrics.register(registry, outbox, clock);
        }
    }

    static final class OutboxMetrics {
        private OutboxMetrics() {
        }

        static OutboxMetrics register(MeterRegistry registry, SpringDataOutboxRepository outbox, Clock clock) {
            Gauge.builder("outbox.pending.events", outbox, SpringDataOutboxRepository::countByPublishedAtIsNullAndParkedAtIsNull)
                .description("Account events written to the outbox and not yet published to Kafka")
                .register(registry);
            // Rows parked right now. Not "outbox.parked.events": that is the relay's counter
            // (outbox_parked_events_total) and Prometheus refuses a gauge and a counter sharing a base name.
            Gauge.builder("outbox.parked.rows", outbox, SpringDataOutboxRepository::countByParkedAtIsNotNull)
                .description("Account events parked right now (payload error, or by an operator with a reason)")
                .register(registry);
            Gauge.builder("outbox.oldest.pending.age.seconds", outbox, repository -> {
                    Instant oldest = repository.oldestPendingOccurredAt();
                    return oldest == null ? 0 : Math.max(0, Duration.between(oldest, clock.instant()).toSeconds());
                })
                .description("Age of the oldest account event waiting for the relay")
                .baseUnit("seconds")
                .register(registry);
            return new OutboxMetrics();
        }
    }

    /**
     * The relay runs in every replica; the advisory lock lets only one of
     * them publish at a time. Off unless accounts.outbox.relay.enabled=true, so
     * a local run or a deployment without Kafka never tries to publish.
     */
    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "accounts.outbox.relay.enabled", havingValue = "true")
    static class RelayConfiguration {

        @Bean
        OutboxRelay outboxRelay(SpringDataOutboxRepository outbox,
                                KafkaTemplate<String, String> kafka,
                                PlatformTransactionManager transactionManager,
                                DataSource dataSource,
                                Clock clock,
                                @Value("${accounts.outbox.relay.batch-size:100}") int batchSize,
                                @Value("${accounts.outbox.relay.send-timeout:PT35S}") Duration sendTimeout,
                                @Value("${accounts.outbox.retention:P7D}") Duration retention,
                                MeterRegistry meters) {
            // Failed sends count in outbox.send.failures{exception}, parks in outbox.parked.events{exception}.
            // The relay lock is session-level on a connection of its own, so no
            // transaction stays open while a send waits on Kafka.
            return new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
                new PostgresSessionRelayLock(dataSource, OutboxRelay.RELAY_LOCK_KEY), clock, batchSize,
                sendTimeout, retention, meters);
        }

        @Bean
        RelaySchedule relaySchedule(OutboxRelay relay) {
            return new RelaySchedule(relay);
        }
    }

    static class RelaySchedule {
        private final OutboxRelay relay;

        RelaySchedule(OutboxRelay relay) {
            this.relay = relay;
        }

        @Scheduled(fixedDelayString = "${accounts.outbox.relay.interval:PT1S}")
        void relay() {
            relay.relayOnce();
        }

        @Scheduled(cron = "${accounts.outbox.purge-cron:0 15 3 * * *}")
        void purge() {
            relay.purgePublished();
        }
    }
}
