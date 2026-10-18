package com.bank.accounts.infrastructure.outbox;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.port.out.AccountEventPort;
import com.bank.accounts.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.DomainEvent;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Transactional outbox: writes each event's envelope in the caller's
 * transaction (MANDATORY), so the account change and its events commit or roll
 * back together. {@link OutboxRelay} ships them to Kafka afterwards.
 */
@Component
public class OutboxAccountEventPublisher implements AccountEventPort {

    private final SpringDataOutboxRepository outbox;
    private final AccountEventEnvelopeFactory envelopes;

    public OutboxAccountEventPublisher(SpringDataOutboxRepository outbox, AccountEventEnvelopeFactory envelopes) {
        this.outbox = outbox;
        this.envelopes = envelopes;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Account account, List<DomainEvent> events) {
        String correlationId = currentCorrelationId();
        String traceparent = MDC.get(CorrelationIdFilter.TRACEPARENT_MDC_KEY);
        outbox.saveAll(events.stream()
            .map(event -> {
                OutboxEventJpaEntity row = envelopes.toOutboxRow(account, event, correlationId);
                row.recordTraceparent(traceparent);
                return row;
            })
            .toList());
    }

    private static String currentCorrelationId() {
        String fromRequest = MDC.get(CorrelationIdFilter.MDC_KEY);
        return fromRequest != null ? fromRequest : UUID.randomUUID().toString();
    }
}
