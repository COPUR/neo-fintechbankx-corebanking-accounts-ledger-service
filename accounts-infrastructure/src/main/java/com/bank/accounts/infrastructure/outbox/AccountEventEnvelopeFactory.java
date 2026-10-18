package com.bank.accounts.infrastructure.outbox;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountEvent;
import com.bank.shared.kernel.domain.DomainEvent;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Turns Account domain events into the public envelope of the AsyncAPI
 * contract api/asyncapi/svc-acc-accounts-ledger.yaml: eventType
 * CoreBanking.Account.&lt;Event&gt;.v1 (OutboxRelay sends every one to the
 * aggregate topic evt.acc.account.v1, keyed by the account id), money as
 * decimal strings, ids only. A freeze reason is not published: it can name
 * an investigation.
 */
public class AccountEventEnvelopeFactory {

    public static final String PRODUCER = "svc-acc-accounts-ledger";
    public static final String AGGREGATE_TYPE = "Account";

    private final ObjectMapper objectMapper;

    public AccountEventEnvelopeFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public OutboxEventJpaEntity toOutboxRow(Account account, DomainEvent event, String correlationId) {
        PublicEvent mapped = map(event);
        UUID eventId = UUID.fromString(event.getEventId());
        String aggregateId = account.getId().value();
        long aggregateVersion = account.getVersion() == null ? 0L : account.getVersion();

        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("eventId", eventId.toString());
        envelope.put("eventType", mapped.eventType());
        envelope.put("occurredAt", event.getOccurredOn().toString());
        envelope.put("aggregateId", aggregateId);
        envelope.put("aggregateVersion", aggregateVersion);
        envelope.put("correlationId", correlationId);
        envelope.put("causationId", null);
        envelope.put("producer", PRODUCER);
        envelope.put("data", mapped.data());

        return new OutboxEventJpaEntity(eventId, AGGREGATE_TYPE, aggregateId, aggregateVersion,
            mapped.eventType(), toJson(envelope), correlationId, event.getOccurredOn());
    }

    static PublicEvent map(DomainEvent event) {
        if (!(event instanceof AccountEvent accountEvent)) {
            throw new IllegalArgumentException("No public contract for event " + event.getClass().getName());
        }
        return switch (accountEvent) {
            case AccountEvent.Opened e -> new PublicEvent("Opened", data(
                "accountId", e.accountId().value(),
                "category", e.category().name(),
                "holderId", e.holder() == null ? null : e.holder().getValue(),
                "accountType", e.type().name(),
                "currency", e.currency(),
                "islamicCompliant", e.islamicCompliant()));
            case AccountEvent.Frozen e -> new PublicEvent("Frozen", data("accountId", e.accountId().value()));
            case AccountEvent.Unfrozen e -> new PublicEvent("Unfrozen", data("accountId", e.accountId().value()));
            case AccountEvent.Closed e -> new PublicEvent("Closed", data("accountId", e.accountId().value()));
            case AccountEvent.Debited e -> new PublicEvent("Debited", data(
                "accountId", e.accountId().value(),
                "journalEntryId", e.journalEntryId().value(),
                "amount", money(e.amount()),
                "balanceAfter", money(e.balanceAfter())));
            case AccountEvent.Credited e -> new PublicEvent("Credited", data(
                "accountId", e.accountId().value(),
                "journalEntryId", e.journalEntryId().value(),
                "amount", money(e.amount()),
                "balanceAfter", money(e.balanceAfter())));
        };
    }

    private static Map<String, Object> money(Money money) {
        return data("amount", money.getAmount().toPlainString(), "currency", money.getCurrency().getCurrencyCode());
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    private String toJson(Map<String, Object> envelope) {
        try {
            return objectMapper.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise account event envelope", e);
        }
    }

    record PublicEvent(String eventName, Map<String, Object> data) {
        String eventType() {
            return "CoreBanking.Account." + eventName + ".v1";
        }
    }
}
