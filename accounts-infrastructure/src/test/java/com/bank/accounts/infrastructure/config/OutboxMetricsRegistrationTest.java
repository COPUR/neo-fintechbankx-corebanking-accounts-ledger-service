package com.bank.accounts.infrastructure.config;

import com.bank.accounts.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The outbox gauges exist only where the relay runs: with the relay off nothing
 * publishes, so outbox_oldest_pending_age_seconds would climb from the first write
 * and OutboxRelayStalled would page the corebanking squad for a relay that was
 * never meant to run.
 */
class OutboxMetricsRegistrationTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
        .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
        .withBean(SpringDataOutboxRepository.class, () -> mock(SpringDataOutboxRepository.class))
        .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void noOutboxGaugeIsRegisteredWhileTheRelayIsOff() {
        context.withUserConfiguration(OutboxConfiguration.class)
            .run(app -> {
                assertThat(app).hasNotFailed();
                MeterRegistry registry = app.getBean(MeterRegistry.class);
                assertThat(registry.find("outbox.pending.events").gauge()).isNull();
                assertThat(registry.find("outbox.parked.rows").gauge()).isNull();
                assertThat(registry.find("outbox.oldest.pending.age.seconds").gauge()).isNull();
            });
    }

    @Test
    void theRelaySwitchAlsoRegistersTheGauges() {
        context.withUserConfiguration(OutboxConfiguration.RelayMetricsConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("accounts.outbox.relay.enabled=true")
            .run(app -> {
                MeterRegistry registry = app.getBean(MeterRegistry.class);
                assertThat(registry.find("outbox.pending.events").gauge()).isNotNull();
                assertThat(registry.find("outbox.parked.rows").gauge()).isNotNull();
                assertThat(registry.find("outbox.oldest.pending.age.seconds").gauge()).isNotNull();
            });
    }

    @Test
    void anyOtherRelaySettingKeepsTheGaugesOff() {
        context.withUserConfiguration(OutboxConfiguration.RelayMetricsConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withPropertyValues("accounts.outbox.relay.enabled=false")
            .run(app -> assertThat(app.getBean(MeterRegistry.class)
                .find("outbox.oldest.pending.age.seconds").gauge()).isNull());
    }
}
