package com.bank.accounts.infrastructure.outbox;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class AccountEventEnvelopeFactoryTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    private final ObjectMapper json = new ObjectMapper();
    private final AccountEventEnvelopeFactory factory = new AccountEventEnvelopeFactory(json);

    public static Account accountRaisingEveryEvent() {
        Account account = Account.openCustomerAccount(AccountId.of("ACC-ENV"), CustomerId.of("CUST-ENV"),
            AccountType.SAVINGS, AED, true, NOW);
        account.credit(Money.of("10.00", AED), JournalEntryId.of("JE-1"), NOW);
        account.debit(Money.of("10.00", AED), JournalEntryId.of("JE-2"), NOW);
        account.freeze("investigation 42", NOW);
        account.unfreeze(NOW);
        account.close(NOW);
        return account;
    }

    @Test
    void everyAccountEventMapsToItsContractType() {
        List<DomainEvent> events = accountRaisingEveryEvent().getDomainEvents();

        assertThat(events).extracting(e -> AccountEventEnvelopeFactory.map(e).eventType()).containsExactly(
            "CoreBanking.Account.Opened.v1", "CoreBanking.Account.Credited.v1", "CoreBanking.Account.Debited.v1",
            "CoreBanking.Account.Frozen.v1", "CoreBanking.Account.Unfrozen.v1", "CoreBanking.Account.Closed.v1");
        assertThat(AccountEventEnvelopeFactory.map(events.get(0)).data())
            .containsEntry("holderId", "CUST-ENV").containsEntry("islamicCompliant", true)
            .containsEntry("accountType", "SAVINGS").containsEntry("category", "CUSTOMER");
        assertThat(AccountEventEnvelopeFactory.map(events.get(1)).data())
            .containsEntry("journalEntryId", "JE-1")
            .containsEntry("amount", Map.of("amount", "10.00", "currency", "AED"))
            .containsEntry("balanceAfter", Map.of("amount", "10.00", "currency", "AED"));
    }

    @Test
    void aFreezeReasonIsNotPublished() {
        DomainEvent frozen = accountRaisingEveryEvent().getDomainEvents().get(3);

        assertThat(AccountEventEnvelopeFactory.map(frozen).data()).containsOnlyKeys("accountId");
    }

    @Test
    void anInternalAccountHasNoHolder() {
        Account clearing = Account.openInternalAccount(AccountId.of("GL-1"), AED, NOW);

        assertThat(AccountEventEnvelopeFactory.map(clearing.getDomainEvents().get(0)).data())
            .containsEntry("holderId", null).containsEntry("category", "INTERNAL");
    }

    @Test
    void outboxRowCarriesTheStandardEnvelope() throws Exception {
        Account account = accountRaisingEveryEvent();
        account.setVersion(3L);
        DomainEvent opened = account.getDomainEvents().getFirst();

        OutboxEventJpaEntity row = factory.toOutboxRow(account, opened, "corr-1");
        JsonNode envelope = json.readTree(row.getPayload());

        assertThat(row.getAggregateId()).isEqualTo("ACC-ENV");
        assertThat(row.getAggregateVersion()).isEqualTo(3L);
        assertThat(row.getEventType()).isEqualTo("CoreBanking.Account.Opened.v1");
        assertThat(envelope.get("eventId").asText()).isEqualTo(opened.getEventId());
        assertThat(envelope.get("producer").asText()).isEqualTo("svc-acc-accounts-ledger");
        assertThat(envelope.get("aggregateId").asText()).isEqualTo("ACC-ENV");
        assertThat(envelope.get("correlationId").asText()).isEqualTo("corr-1");
        assertThat(envelope.get("occurredAt").asText()).isEqualTo("2026-10-17T12:00:00Z");
        assertThat(envelope.has("causationId")).isTrue();
    }

    @Test
    void aForeignEventHasNoContract() {
        DomainEvent foreign = new DomainEvent() {
        };

        assertThatThrownBy(() -> AccountEventEnvelopeFactory.map(foreign))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
