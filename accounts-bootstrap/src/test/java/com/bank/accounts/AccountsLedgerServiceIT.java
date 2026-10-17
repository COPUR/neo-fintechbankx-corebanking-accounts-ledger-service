package com.bank.accounts;

import com.bank.accounts.infrastructure.outbox.OutboxRelay;
import com.bank.accounts.infrastructure.outbox.PostgresSessionRelayLock;
import com.bank.accounts.infrastructure.outbox.SpringDataOutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the whole service against PostgreSQL: Flyway builds
 * sc_acc_accounts_ledger, Hibernate validates the entities against it, and
 * accounts are opened, funded and moved over HTTP with their events landing
 * in the outbox and then on (a mocked) Kafka.
 */
@SpringBootTest(properties = {
    "accounts.outbox.relay.enabled=false",
    // Test configuration: the test database has no TLS (TlsEnforcementProfilesTest covers the assertion).
    "fintechbankx.tls.enforce=false"
})
@AutoConfigureMockMvc
class AccountsLedgerServiceIT {

    private static final String SCHEMA = "sc_acc_accounts_ledger";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired SpringDataOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @MockBean KafkaTemplate<String, String> kafka;

    private String clearing;

    @BeforeEach
    void cleanTablesAndOpenTheClearingAccount() throws Exception {
        // As the schema owner: the runtime role may not delete ledger rows.
        JdbcTemplate owner = PostgresTestDatabase.owner();
        owner.update("delete from " + SCHEMA + ".outbox_event");
        owner.update("delete from " + SCHEMA + ".posting_line");
        owner.update("delete from " + SCHEMA + ".journal_entry");
        owner.update("delete from " + SCHEMA + ".account");
        clearing = open(null, "INTERNAL", "k-clearing");
    }

    @Test
    void theRuntimeRoleCannotRunDdlNorChangeOrDeleteTheJournal() {
        assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo(PostgresTestDatabase.RUNTIME_ROLE);
        JdbcTemplate runtime = PostgresTestDatabase.runtime();

        assertThatThrownBy(() -> runtime.execute("create table " + SCHEMA + ".shadow (id int)"))
            .rootCause().hasMessageContaining("permission denied for schema " + SCHEMA);
        assertThatThrownBy(() -> runtime.update("update " + SCHEMA + ".posting_line set amount = 1"))
            .rootCause().hasMessageContaining("permission denied for table posting_line");
        assertThatThrownBy(() -> runtime.update("delete from " + SCHEMA + ".journal_entry"))
            .rootCause().hasMessageContaining("permission denied for table journal_entry");
        assertThatThrownBy(() -> runtime.update("delete from " + SCHEMA + ".account"))
            .rootCause().hasMessageContaining("permission denied for table account");
    }

    @Test
    void openFundMoveAndReadAnAccountTheWayPaymentsDo() throws Exception {
        String alice = open("CUST-A", "CURRENT", "k-a");
        String bob = open("CUST-B", "SAVINGS", "k-b");
        book(entry("FUND-1", clearing, alice, "100.00")).andExpect(status().isCreated());

        book(entry("PAY-1", alice, bob, "40.00"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.replay").value(false));
        book(entry("PAY-1", alice, bob, "40.00"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.replay").value(true));

        mvc.perform(asService(get("/api/v1/accounts/" + alice), "svc-pay-initiation-settlement"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.customerId").value("CUST-A"))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.availableBalance").value(60.00))
            .andExpect(jsonPath("$.debitAllowed").value(true));
        mvc.perform(asCustomer(get("/api/v1/accounts/" + bob), "CUST-B"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.availableBalance").value(40.00));
        mvc.perform(asCustomer(get("/api/v1/accounts/" + bob), "CUST-A"))
            .andExpect(status().isNotFound());

        BigDecimal total = jdbc.queryForObject("select sum(booked_balance) from " + SCHEMA + ".account", BigDecimal.class);
        assertThat(total).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".journal_entry", Integer.class)).isEqualTo(2);
    }

    @Test
    void anOverdraftBooksNothingAndTheSameReferenceWithOtherLinesIsAConflict() throws Exception {
        String alice = open("CUST-A", "CURRENT", "k-a");
        book(entry("FUND-1", clearing, alice, "10.00")).andExpect(status().isCreated());
        long eventsBefore = outbox.count();

        book(entry("PAY-2", alice, clearing, "10.01"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"));
        book(entry("FUND-1", clearing, alice, "11.00"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(outbox.count()).isEqualTo(eventsBefore);
        assertThat(jdbc.queryForObject("select booked_balance from " + SCHEMA + ".account where account_id = ?",
            BigDecimal.class, alice)).isEqualByComparingTo("10.00");
    }

    /**
     * Twenty debits of 10.00 race against a balance of 100.00. Row locks in id
     * order serialize them: exactly ten book, ten are refused, nothing is lost
     * or overdrawn, and nothing deadlocks.
     */
    @Test
    void concurrentPostingsNeitherLoseUpdatesNorOverdraw() throws Exception {
        String alice = open("CUST-A", "CURRENT", "k-a");
        String bob = open("CUST-B", "CURRENT", "k-b");
        book(entry("FUND-1", clearing, alice, "100.00")).andExpect(status().isCreated());

        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Callable<Integer>> calls = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            String reference = "RACE-" + i;
            calls.add(() -> book(entry(reference, alice, bob, "10.00")).andReturn().getResponse().getStatus());
        }
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> result : pool.invokeAll(calls)) {
            statuses.add(result.get());
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(code -> code == 201).hasSize(10);
        assertThat(statuses).filteredOn(code -> code == 422).hasSize(10);
        assertThat(jdbc.queryForObject("select booked_balance from " + SCHEMA + ".account where account_id = ?",
            BigDecimal.class, alice)).isEqualByComparingTo("0.00");
        assertThat(jdbc.queryForObject("select booked_balance from " + SCHEMA + ".account where account_id = ?",
            BigDecimal.class, bob)).isEqualByComparingTo("100.00");
    }

    @Test
    void aFrozenAccountTakesNoPostingsUntilUnfrozen() throws Exception {
        String alice = open("CUST-A", "CURRENT", "k-a");
        mvc.perform(asBanker(post("/api/v1/accounts/" + alice + "/freeze"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"court order\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("FROZEN"));

        book(entry("FUND-1", clearing, alice, "1.00"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_OPERATIONAL"));

        mvc.perform(asBanker(post("/api/v1/accounts/" + alice + "/unfreeze"))).andExpect(status().isOk());
        book(entry("FUND-1", clearing, alice, "1.00")).andExpect(status().isCreated());
    }

    @Test
    void openingTwiceWithTheSameKeyReturnsTheSameAccount() throws Exception {
        String first = open("CUST-A", "CURRENT", "k-same");
        String second = open("CUST-A", "CURRENT", "k-same");

        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("select count(*) from " + SCHEMA + ".account where holder_id = 'CUST-A'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void theRelayPublishesEveryAccountEventToTheAggregateTopicKeyedByAccountId() throws Exception {
        String alice = open("CUST-A", "CURRENT", "k-a");
        book(entry("FUND-1", clearing, alice, "5.00")).andExpect(status().isCreated());
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            new PostgresSessionRelayLock(dataSource, OutboxRelay.RELAY_LOCK_KEY), Clock.systemUTC(), 100,
            Duration.ofSeconds(5), Duration.ofDays(7), new SimpleMeterRegistry());

        assertThat(relay.relayOnce()).isEqualTo(4);

        assertThat(sent).allSatisfy(record -> assertThat(record.topic()).isEqualTo("evt.acc.account.v1"));
        assertThat(sent).extracting(ProducerRecord::key).containsExactly(clearing, alice, clearing, alice);
        List<String> types = sent.stream().map(record -> new String(record.headers().lastHeader("eventType").value()))
            .toList();
        assertThat(types).containsExactly("CoreBanking.Account.Opened.v1", "CoreBanking.Account.Opened.v1",
            "CoreBanking.Account.Debited.v1", "CoreBanking.Account.Credited.v1");
        JsonNode credited = json.readTree(sent.get(3).value());
        assertThat(credited.at("/data/balanceAfter/amount").asText()).isEqualTo("5.00");
        assertThat(credited.get("producer").asText()).isEqualTo("svc-acc-accounts-ledger");
    }

    private String open(String customerId, String type, String key) throws Exception {
        String body = customerId == null
            ? "{\"accountType\":\"" + type + "\",\"currency\":\"AED\"}"
            : "{\"customerId\":\"" + customerId + "\",\"accountType\":\"" + type + "\",\"currency\":\"AED\"}";
        String response = mvc.perform(asBanker(post("/api/v1/accounts")).header("x-idempotency-key", key)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        return json.readTree(response).get("accountId").asText();
    }

    private static String entry(String reference, String debit, String credit, String amount) {
        return """
            {"reference":"%s","currency":"AED","lines":[
              {"accountId":"%s","side":"DEBIT","amount":%s},
              {"accountId":"%s","side":"CREDIT","amount":%s}]}
            """.formatted(reference, debit, amount, credit, amount);
    }

    private org.springframework.test.web.servlet.ResultActions book(String body) throws Exception {
        return mvc.perform(asService(post("/api/v1/journal-entries"), "svc-pay-initiation-settlement")
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private static MockHttpServletRequestBuilder asCustomer(MockHttpServletRequestBuilder request, String customerId) {
        return request.with(jwt().jwt(j -> j.subject("kc-" + customerId).claim("customer_id", customerId))
            .authorities(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
    }

    private static MockHttpServletRequestBuilder asService(MockHttpServletRequestBuilder request, String clientId) {
        return request.header("x-fapi-interaction-id", "it-interaction-svc")
            .with(jwt().jwt(j -> j.subject("service-account-" + clientId).claim("azp", clientId))
                .authorities(new SimpleGrantedAuthority("ROLE_SERVICE")));
    }

    private static MockHttpServletRequestBuilder asBanker(MockHttpServletRequestBuilder request) {
        return request.with(jwt().jwt(j -> j.subject("banker-1")).authorities(new SimpleGrantedAuthority("ROLE_BANKER")));
    }
}
