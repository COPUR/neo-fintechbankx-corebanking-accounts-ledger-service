package com.bank.accounts.infrastructure.contract;

import com.bank.accounts.domain.Account;
import com.bank.accounts.domain.AccountId;
import com.bank.accounts.domain.AccountType;
import com.bank.accounts.domain.JournalEntry;
import com.bank.accounts.domain.JournalEntryId;
import com.bank.accounts.domain.PostingLine;
import com.bank.accounts.infrastructure.web.dto.AccountResponse;
import com.bank.accounts.infrastructure.web.dto.JournalEntryResponse;
import com.bank.shared.kernel.domain.CustomerId;
import com.bank.shared.kernel.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * api/openapi/accounts-ledger.yaml against the code: the paths the
 * controllers serve, and response bodies built by the real DTOs checked
 * against the published schemas (required, nullable, types, enums, no
 * undeclared property). The account schema also keeps the fields the
 * payments and mandates adapters read.
 */
class AccountsOpenApiContractTest {

    private static final Currency AED = Currency.getInstance("AED");
    private static final Instant NOW = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void definesTheImplementedEndpoints() throws IOException {
        String spec = loadSpec();

        assertThat(spec).contains("\n  /api/v1/accounts:\n");
        assertThat(spec).contains("\n  /api/v1/accounts/{accountId}:\n");
        assertThat(spec).contains("\n  /api/v1/accounts/{accountId}/freeze:\n");
        assertThat(spec).contains("\n  /api/v1/accounts/{accountId}/unfreeze:\n");
        assertThat(spec).contains("\n  /api/v1/accounts/{accountId}/close:\n");
        assertThat(spec).contains("\n  /api/v1/journal-entries:\n");
        assertThat(spec).contains("\n  /api/v1/journal-entries/{journalEntryId}:\n");
    }

    @Test
    @SuppressWarnings("unchecked")
    void protectedOperationsUseBearerTokensAndDeclareDpopAsOptional() throws IOException {
        Map<String, Object> spec = new Yaml().load(loadSpec());
        Map<String, Object> components = (Map<String, Object>) spec.get("components");
        Map<String, Object> parameters = (Map<String, Object>) components.get("parameters");

        assertThat((Map<String, Object>) parameters.get("DPoP")).containsEntry("required", false);
        assertThat((Map<String, Object>) parameters.get("IdempotencyKey")).containsEntry("required", true);
        assertThat((Map<String, Object>) components.get("securitySchemes")).containsOnlyKeys("bearerAuth");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theAccountSchemaKeepsTheFieldsConsumersAlreadyRead() throws IOException {
        Map<String, Object> schema = schema("AccountResponse");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKeys("accountId", "customerId", "status", "currency", "availableBalance",
            "debitAllowed", "creditAllowed");
        assertThat((List<String>) schema.get("required")).contains("accountId", "status", "currency",
            "availableBalance", "debitAllowed", "creditAllowed");
    }

    @Test
    void customerAndInternalAccountsMatchThePublishedSchema() throws IOException {
        Account customer = Account.openCustomerAccount(AccountId.of("ACC-1"), CustomerId.of("CUST-1"),
            AccountType.CURRENT, AED, true, NOW);
        Account internal = Account.openInternalAccount(AccountId.of("GL-1"), AED, NOW);

        assertMatches("AccountResponse", AccountResponse.from(customer));
        assertMatches("AccountResponse", AccountResponse.from(internal));
    }

    @Test
    void aJournalEntryMatchesThePublishedSchema() throws IOException {
        JournalEntry entry = JournalEntry.book(JournalEntryId.of("JE-1"), "PAY-1", List.of(
            PostingLine.debit(AccountId.of("ACC-1"), Money.of("1.50", AED)),
            PostingLine.credit(AccountId.of("ACC-2"), Money.of("1.50", AED))), null, NOW);

        assertMatches("JournalEntryResponse", JournalEntryResponse.from(entry, true));
    }

    @SuppressWarnings("unchecked")
    private static void assertMatches(String schemaName, Object response) throws IOException {
        Map<String, Object> schema = schema(schemaName);
        Map<String, Map<String, Object>> properties = (Map<String, Map<String, Object>>) schema.get("properties");
        List<String> required = (List<String>) schema.get("required");
        ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .featuresToDisable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        JsonNode body = mapper.valueToTree(response);

        body.fieldNames().forEachRemaining(field ->
            assertThat(properties).as("property %s is declared in %s", field, schemaName).containsKey(field));
        for (String name : required) {
            assertThat(body.hasNonNull(name)).as("required property %s is present and not null", name).isTrue();
        }
        properties.forEach((name, definition) -> {
            JsonNode value = body.get(name);
            if (value == null || value.isNull()) {
                assertThat(required).as("%s may be absent", name).doesNotContain(name);
                assertThat(value == null || Boolean.TRUE.equals(definition.get("nullable")))
                    .as("%s is null, so the schema must declare it nullable", name).isTrue();
                return;
            }
            switch (String.valueOf(definition.get("type"))) {
                case "string" -> assertThat(value.isTextual()).as("%s is a string", name).isTrue();
                case "number" -> assertThat(value.isNumber()).as("%s is a number", name).isTrue();
                case "boolean" -> assertThat(value.isBoolean()).as("%s is a boolean", name).isTrue();
                case "array" -> assertThat(value.isArray()).as("%s is an array", name).isTrue();
                default -> { }
            }
            if (definition.get("enum") instanceof List<?> values) {
                assertThat(values.stream().map(String::valueOf).toList()).as("%s value is in the enum", name)
                    .contains(value.asText());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> schema(String name) throws IOException {
        Map<String, Object> spec = new Yaml().load(loadSpec());
        return (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) spec.get("components"))
            .get("schemas")).get(name);
    }

    private static String loadSpec() throws IOException {
        Path path = List.of(Path.of("api/openapi/accounts-ledger.yaml"), Path.of("../api/openapi/accounts-ledger.yaml"))
            .stream().filter(Files::exists).findFirst()
            .orElseThrow(() -> new AssertionError("api/openapi/accounts-ledger.yaml not found"));
        return Files.readString(path);
    }
}
