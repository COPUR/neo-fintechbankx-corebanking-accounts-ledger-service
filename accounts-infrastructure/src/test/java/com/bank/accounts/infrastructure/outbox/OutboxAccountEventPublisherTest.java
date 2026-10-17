package com.bank.accounts.infrastructure.outbox;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.infrastructure.web.CorrelationIdFilter;
import com.bank.shared.kernel.domain.CustomerId;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.time.Instant;
import java.util.Currency;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class OutboxAccountEventPublisherTest {

    private final SpringDataOutboxRepository outbox = mock(SpringDataOutboxRepository.class);
    private final OutboxAccountEventPublisher publisher = new OutboxAccountEventPublisher(outbox,
        new AccountEventEnvelopeFactory(new ObjectMapper()));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void rowsCarryTheRequestsInteractionIdAndTraceparent() {
        MDC.put(CorrelationIdFilter.MDC_KEY, "corr-pub");
        MDC.put(CorrelationIdFilter.TRACEPARENT_MDC_KEY, "00-11111111111111111111111111111111-2222222222222222-01");
        Account account = account();

        publisher.publish(account, List.copyOf(account.getDomainEvents()));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getCorrelationId()).isEqualTo("corr-pub");
            assertThat(row.getTraceparent()).isEqualTo("00-11111111111111111111111111111111-2222222222222222-01");
            assertThat(row.getEventType()).isEqualTo("CoreBanking.Account.Opened.v1");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void withoutARequestAnInteractionIdIsGeneratedAndNoTraceparentIsStored() {
        Account account = account();

        publisher.publish(account, List.copyOf(account.getDomainEvents()));

        ArgumentCaptor<List<OutboxEventJpaEntity>> rows = ArgumentCaptor.forClass(List.class);
        verify(outbox).saveAll(rows.capture());
        assertThat(rows.getValue()).singleElement().satisfies(row -> {
            assertThat(row.getCorrelationId()).matches("[0-9a-f-]{36}");
            assertThat(row.getTraceparent()).isNull();
        });
    }

    private static Account account() {
        return Account.openCustomerAccount(AccountId.of("ACC-PUB"), CustomerId.of("CUST-PUB"), AccountType.CURRENT,
            Currency.getInstance("AED"), false, Instant.parse("2026-10-17T12:00:00Z"));
    }
}
