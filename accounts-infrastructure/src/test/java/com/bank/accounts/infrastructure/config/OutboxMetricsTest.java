package com.bank.accounts.infrastructure.config;

import com.bank.accounts.infrastructure.outbox.SpringDataOutboxRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OutboxMetricsTest {

    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void pendingAndOldestAgeAreExported() {
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        when(outbox.countByPublishedAtIsNullAndParkedAtIsNull()).thenReturn(4L);
        when(outbox.countByParkedAtIsNotNull()).thenReturn(1L);
        when(outbox.oldestPendingOccurredAt()).thenReturn(NOW.minusSeconds(90));
        when(outbox.oldestPendingDiscardRequestedAt()).thenReturn(NOW.minusSeconds(600));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxConfiguration.RelayMetricsConfiguration().outboxMetrics(registry, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.get("outbox.pending.events").gauge().value()).isEqualTo(4.0);
        // outbox.parked.events is the relay's counter (outbox_parked_events_total, Kafka guide 5f7d546), never a gauge.
        assertThat(registry.find("outbox.parked.events").gauge()).isNull();
        assertThat(registry.get("outbox.parked.rows").gauge().value()).isEqualTo(1.0);
        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(90.0);
        assertThat(registry.get("outbox.discard.pending.age.seconds").gauge().value()).isEqualTo(600.0);
    }

    @Test
    void anEmptyOutboxHasNoAge() {
        SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new OutboxConfiguration.RelayMetricsConfiguration().outboxMetrics(registry, outbox, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isZero();
        assertThat(registry.get("outbox.discard.pending.age.seconds").gauge().value()).isZero();
    }
}
