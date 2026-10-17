package com.bank.accounts.infrastructure.contract;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.infrastructure.outbox.OutboxEventJpaEntity;
import com.bank.accounts.infrastructure.outbox.OutboxRelayRecords;
import com.bank.accounts.infrastructure.outbox.AccountEventEnvelopeFactory;
import com.bank.accounts.infrastructure.outbox.AccountEventEnvelopeFactoryTest;
import com.bank.shared.kernel.domain.DomainEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every event the service can emit, built by the real envelope factory, must
 * match api/asyncapi/svc-acc-accounts-ledger.yaml: the relay's topic = the
 * one aggregate channel's address, eventType header const, eventType and producer constants, envelope and data required
 * fields present, no undeclared data fields, nulls only where declared,
 * money as decimal strings with an ISO currency.
 */
class AccountEventContractTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AccountEventEnvelopeFactory factory = new AccountEventEnvelopeFactory(json);

    /**
     * Governance pre-release rule: 1.0.0 until the spec first lands on the
     * catalog's main. ADR-024 servers: msk (mskIam) and local (Strimzi, mtls).
     */
    @Test
    @SuppressWarnings("unchecked")
    void theSpecIsPreReleaseAndDeclaresTheAdr024Servers() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(specPath()));
        Map<String, Object> servers = (Map<String, Object>) spec.get("servers");

        assertThat(((Map<String, Object>) spec.get("info")).get("version")).isEqualTo("1.0.0");
        assertThat(servers).containsOnlyKeys("msk", "local");
        assertThat(((Map<String, Object>) servers.get("msk")).get("security"))
            .isEqualTo(List.of(Map.of("$ref", "#/components/securitySchemes/mskIam")));
        assertThat(((Map<String, Object>) servers.get("local")).get("security"))
            .isEqualTo(List.of(Map.of("$ref", "#/components/securitySchemes/mtls")));
        Map<String, Object> schemes = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("securitySchemes");
        assertThat(((Map<String, Object>) schemes.get("mskIam")).get("x-sasl-mechanism")).isEqualTo("AWS_MSK_IAM");
    }

    @Test
    @SuppressWarnings("unchecked")
    void everyEmittedEventMatchesTheAsyncApiContract() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(specPath()));
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> schemas = (Map<String, Object>) components.get("schemas");
        Map<String, Object> messages = (Map<String, Object>) components.get("messages");
        Map<String, Object> channels = (Map<String, Object>) spec.get("channels");
        Map<String, Object> envelope = resolve((Map<String, Object>) schemas.get("EventEnvelope"));

        List<String> seen = new ArrayList<>();
        for (Account account : accountsRaisingEveryEvent()) {
            for (DomainEvent event : account.getDomainEvents()) {
                OutboxEventJpaEntity row = factory.toOutboxRow(account, event, "corr-contract");
                Map<String, Object> body = json.readValue(row.getPayload(), Map.class);
                Map.Entry<String, Object> message = messages.entrySet().stream()
                    .filter(m -> row.getEventType().equals(((Map<String, Object>) m.getValue()).get("title")))
                    .findFirst().orElseThrow(() -> new AssertionError("No message for " + row.getEventType()));
                seen.add(row.getEventType());

                Map<String, Object> channel = channels.values().stream().map(c -> (Map<String, Object>) c)
                    .filter(c -> ((Map<String, Object>) c.get("messages")).containsKey(message.getKey()))
                    .findFirst().orElseThrow();
                assertThat(OutboxRelayRecords.topicOf(row)).as("topic of %s", row.getEventType()).isEqualTo(channel.get("address"));
                Map<String, Object> headerOverlay = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                    ((Map<String, Object>) message.getValue()).get("headers")).get("allOf")).get(1);
                assertThat(((Map<String, Object>) ((Map<String, Object>) headerOverlay.get("properties")).get("eventType")).get("const"))
                    .as("eventType record header of %s", row.getEventType()).isEqualTo(row.getEventType());

                assertThat(body.keySet()).containsAll((Collection<String>) envelope.get("required"));
                assertThat(body.keySet()).isSubsetOf(((Map<String, Object>) envelope.get("properties")).keySet());
                Map<String, Object> overlay = (Map<String, Object>) ((List<Object>) ((Map<String, Object>)
                    ((Map<String, Object>) message.getValue()).get("payload")).get("allOf")).get(1);
                Map<String, Object> overlayProps = (Map<String, Object>) overlay.get("properties");
                assertThat(body.get("eventType")).isEqualTo(((Map<String, Object>) overlayProps.get("eventType")).get("const"));
                assertThat(body.get("producer")).isEqualTo(((Map<String, Object>) overlayProps.get("producer")).get("const"));

                String dataRef = (String) ((Map<String, Object>) overlayProps.get("data")).get("$ref");
                Map<String, Object> dataSchema = (Map<String, Object>) schemas.get(dataRef.substring(dataRef.lastIndexOf('/') + 1));
                Map<String, Object> data = (Map<String, Object>) body.get("data");
                Map<String, Object> declared = (Map<String, Object>) dataSchema.get("properties");
                assertThat(data.keySet()).as("data of %s", row.getEventType())
                    .containsAll((Collection<String>) dataSchema.get("required"))
                    .isSubsetOf(declared.keySet());
                data.forEach((field, value) -> assertMatches(row.getEventType() + "." + field,
                    (Map<String, Object>) declared.get(field), value, schemas));
            }
        }
        assertThat(seen.stream().distinct().toList()).containsExactlyInAnyOrder(messages.keySet().stream()
            .map(name -> (String) ((Map<String, Object>) messages.get(name)).get("title")).toArray(String[]::new));
    }

    /** One topic per aggregate (ADR-019): a single channel evt.acc.account.v1 carries every message. */
    @Test
    @SuppressWarnings("unchecked")
    void oneAggregateChannelCarriesEveryAccountEvent() throws Exception {
        Map<String, Object> spec = new Yaml().load(Files.readString(specPath()));
        Map<String, Object> channels = (Map<String, Object>) spec.get("channels");
        Map<String, Object> messages = (Map<String, Object>) ((Map<String, Object>) spec.get("components")).get("messages");

        assertThat(channels).containsOnlyKeys("account");
        Map<String, Object> channel = (Map<String, Object>) channels.get("account");
        assertThat(channel.get("address")).isEqualTo("evt.acc.account.v1");
        assertThat(((Map<String, Object>) ((Map<String, Object>) channel.get("bindings")).get("kafka")).get("topic"))
            .isEqualTo("evt.acc.account.v1");
        assertThat(((Map<String, Object>) channel.get("messages")).keySet()).containsExactlyInAnyOrderElementsOf(messages.keySet());
    }

    @SuppressWarnings("unchecked")
    private static void assertMatches(String path, Map<String, Object> property, Object value, Map<String, Object> schemas) {
        Object type = property.get("type");
        if (value == null) {
            assertThat(type).as("%s is null but not declared nullable", path).isInstanceOf(List.class);
            assertThat((List<Object>) type).as(path).contains("null");
            return;
        }
        if (property.containsKey("allOf")) {
            String ref = (String) ((Map<String, Object>) ((List<Object>) property.get("allOf")).get(0)).get("$ref");
            Map<String, Object> money = resolve((Map<String, Object>) schemas.get(ref.substring(ref.lastIndexOf('/') + 1)));
            Map<String, Object> amount = (Map<String, Object>) value;
            assertThat(amount.keySet()).as(path).containsExactlyInAnyOrderElementsOf((Collection<String>) money.get("required"));
            assertThat((String) amount.get("amount")).as(path).matches("^-?[0-9]+(\\.[0-9]+)?$");
            assertThat((String) amount.get("currency")).as(path).matches("^[A-Z]{3}$");
            return;
        }
        if ("boolean".equals(type)) {
            assertThat(value).as(path).isInstanceOf(Boolean.class);
            return;
        }
        assertThat(value).as(path).isInstanceOf(String.class);
        if (property.get("pattern") instanceof String pattern) {
            assertThat((String) value).as(path).matches(pattern);
        }
        if (property.get("enum") instanceof List<?> allowed) {
            assertThat(allowed.contains(value)).as("%s value %s is in the enum", path, value).isTrue();
        }
    }

    /** A customer account raising every event, plus an internal account (null holder). */
    private static List<Account> accountsRaisingEveryEvent() {
        return List.of(AccountEventEnvelopeFactoryTest.accountRaisingEveryEvent(),
            Account.openInternalAccount(AccountId.of("GL-C"), Currency.getInstance("AED"),
                Instant.parse("2026-10-17T12:00:00Z")));
    }

    /** A schema, or the common/event-envelope.yaml schema it references (copy of the catalog file). */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolve(Map<String, Object> schema) {
        Object ref = schema.get("$ref");
        if (!(ref instanceof String text) || !text.startsWith("./common/event-envelope.yaml#/")) {
            return schema;
        }
        try {
            Map<String, Object> common = new Yaml().load(Files.readString(specPath().resolveSibling("common/event-envelope.yaml")));
            return (Map<String, Object>) common.get(text.substring(text.indexOf("#/") + 2));
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static Path specPath() {
        return List.of(Path.of("api/asyncapi/svc-acc-accounts-ledger.yaml"),
                Path.of("../api/asyncapi/svc-acc-accounts-ledger.yaml"))
            .stream().filter(Files::exists).findFirst()
            .orElseThrow(() -> new AssertionError("api/asyncapi/svc-acc-accounts-ledger.yaml not found"));
    }
}
