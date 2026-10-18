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
import org.junit.jupiter.api.Timeout;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
        // TRUNCATE: the discard guard refuses deleting unpublished outbox rows, row by row.
        owner.execute("truncate " + SCHEMA + ".outbox_discard_audit, " + SCHEMA + ".outbox_event");
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
    void theOpenAndBookResponsesAnswerTheStoredInstant() throws Exception {
        String response = mvc.perform(asBanker(post("/api/v1/accounts")).header("x-idempotency-key", "k-time")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"customerId\":\"CUST-T\",\"accountType\":\"CURRENT\",\"currency\":\"AED\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();
        JsonNode opened = json.readTree(response);
        String account = opened.get("accountId").asText();
        assertThat(Instant.parse(opened.get("openedAt").asText())).isEqualTo(jdbc.queryForObject(
            "select opened_at from " + SCHEMA + ".account where account_id = ?", Instant.class, account));

        JsonNode booked = json.readTree(book(entry("TIME-1", clearing, account, "1.00"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());
        assertThat(Instant.parse(booked.get("bookedAt").asText())).isEqualTo(jdbc.queryForObject(
            "select booked_at from " + SCHEMA + ".journal_entry where reference = 'TIME-1'", Instant.class));
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

    /**
     * The runbook discard, as an operator runs it: park, then request the discard in SQL. The relay
     * audits it with the requesting database user (not the value the UPDATE supplies), never sends
     * it, releases the account's later events, and counts it; the runtime role cannot rewrite the audit.
     */
    @Test
    void anOperatorDiscardIsAuditedWithWhoAskedAndReleasesTheAccountsLaterEvents() throws Exception {
        String alice = open("CUST-D", "CURRENT", "k-d");
        book(entry("FUND-D", clearing, alice, "5.00")).andExpect(status().isCreated());
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, alice);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-1: test'"
            + " where event_id = ? and published_at is null and parked_at is null", opened);
        assertThatThrownBy(() -> owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now()"
            + " where event_id = ?", opened)).hasMessageContaining("ck_outbox_discard_reason");
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-1: consumers agreed', discard_requested_by = 'someone-else'"
            + " where event_id = ? and parked_at is not null and published_at is null", opened);
        List<ProducerRecord<String, String>> sent = new ArrayList<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            sent.add(invocation.getArgument(0));
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            new PostgresSessionRelayLock(dataSource, OutboxRelay.RELAY_LOCK_KEY), Clock.systemUTC(), 100,
            Duration.ofSeconds(5), Duration.ofDays(7), meters);

        assertThat(relay.relayOnce()).isEqualTo(3);
        assertThat(relay.relayOnce()).isZero();

        assertThat(sent).extracting(ProducerRecord::key).containsExactly(clearing, clearing, alice);
        assertThat(sent.get(2).headers().lastHeader("eventType").value())
            .asString().isEqualTo("CoreBanking.Account.Credited.v1");
        Map<String, Object> audit = owner.queryForMap("select * from " + SCHEMA + ".outbox_discard_audit");
        assertThat(audit).containsEntry("event_id", opened)
            .containsEntry("aggregate_id", alice)
            .containsEntry("event_type", "CoreBanking.Account.Opened.v1")
            .containsEntry("park_reason", "INC-1: test")
            .containsEntry("discard_reason", "INC-1: consumers agreed")
            .containsEntry("requested_by", PostgresTestDatabase.ownerUser());
        assertThat(owner.queryForObject("select published_at is not null and parked_at is null from " + SCHEMA
            + ".outbox_event where event_id = ?", Boolean.class, opened)).isTrue();
        assertThat(meters.get("outbox.discarded.events").counter().count()).isEqualTo(1.0);
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE")) {
            assertThat(jdbc.queryForObject("select has_table_privilege(current_user, ?, ?)", Boolean.class,
                SCHEMA + ".outbox_discard_audit", privilege)).as(privilege).isFalse();
        }
        assertThat(jdbc.queryForObject("select has_table_privilege(current_user, ?, 'INSERT')", Boolean.class,
            SCHEMA + ".outbox_discard_audit")).isTrue();
    }

    /**
     * DevSecOps on 6bd4d32: the service's own database role must not be able to name another
     * requester, neither by inserting an outbox row that already carries a discard request nor by
     * writing an audit row itself. Runs as the runtime role (jdbc), the owner plays the operator.
     */
    @Test
    void theRuntimeRoleCannotForgeTheDiscardRequesterNorTheAuditRow() throws Exception {
        String bob = open("CUST-F", "CURRENT", "k-f");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, bob);

        // 1. An INSERT that carries a request is refused, whoever it names.
        assertThatThrownBy(() -> jdbc.update("insert into " + SCHEMA + ".outbox_event (event_id, aggregate_type,"
            + " aggregate_id, aggregate_version, event_type, payload, correlation_id, occurred_at, parked_at,"
            + " park_reason, discard_requested_at, discard_reason, discard_requested_by) values (?, 'Account', ?, 9,"
            + " 'CoreBanking.Account.Debited.v1', '{}'::jsonb, 'c', now(), now(), 'forged', now(), 'forged',"
            + " 'ops_alice')", UUID.randomUUID(), bob))
            .hasMessageContaining("outbox discard can only be requested by UPDATE");

        // 2a. An audit row for an event without a pending request is refused.
        String forgedAudit = "insert into " + SCHEMA + ".outbox_discard_audit (event_id, aggregate_type, aggregate_id,"
            + " aggregate_version, event_type, payload, occurred_at, parked_at, park_reason, discard_reason,"
            + " requested_by, requested_at, discarded_at) values (?, 'X', 'other', 0, 'Forged', '{}'::jsonb, now(),"
            + " now(), 'f', 'forged', 'ops_alice', now() - interval '1 day', now() - interval '1 day')";
        assertThatThrownBy(() -> jdbc.update(forgedAudit, opened)).hasMessageContaining("has no pending discard request");

        // 2b. With a real request, every column the INSERT supplies is replaced from the outbox row.
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-2: test'"
            + " where event_id = ?", opened);
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-2: consumers agreed' where event_id = ?", opened);
        jdbc.update(forgedAudit, opened);
        Map<String, Object> audit = owner.queryForMap("select * from " + SCHEMA + ".outbox_discard_audit"
            + " where event_id = ?", opened);
        assertThat(audit).containsEntry("aggregate_id", bob)
            .containsEntry("event_type", "CoreBanking.Account.Opened.v1")
            .containsEntry("park_reason", "INC-2: test")
            .containsEntry("discard_reason", "INC-2: consumers agreed")
            .containsEntry("requested_by", PostgresTestDatabase.ownerUser());
        assertThat(owner.queryForObject("select requested_at = o.discard_requested_at from " + SCHEMA
            + ".outbox_discard_audit a join " + SCHEMA + ".outbox_event o using (event_id) where event_id = ?",
            Boolean.class, opened)).isTrue();

        // 3. The service role can neither name another requester nor request, change or withdraw one.
        for (String change : List.of("discard_requested_by = 'ops_alice'", "discard_reason = 'forged'",
                "discard_requested_at = null")) {
            assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set " + change
                + " where event_id = ?", opened)).as(change)
                .rootCause().hasMessageContaining("the service role cannot request, change or withdraw an outbox discard");
        }
    }

    /**
     * DevSecOps on b5edc1c (B1): while a discard is pending, the service role must not be able to
     * rewrite what the audit will copy, take the row out of the outbox without an audit row, or
     * delete an unpublished row; the requester's time is the database's, not the statement's (S2).
     */
    @Test
    void aPendingDiscardRequestFreezesTheRowForTheServiceRole() throws Exception {
        String carol = open("CUST-P", "CURRENT", "k-p");
        String other = open("CUST-Q", "CURRENT", "k-q");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        String byType = "select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'";
        UUID opened = owner.queryForObject(byType, UUID.class, carol);
        UUID otherOpened = owner.queryForObject(byType, UUID.class, other);

        // The service role cannot request a discard under its own name either.
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-3: test'"
            + " where event_id = ?", opened);
        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'runtime' where event_id = ?", opened))
            .rootCause().hasMessageContaining("the service role cannot request, change or withdraw an outbox discard");

        // A backdated request is stamped with the database's time and login.
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now() - interval '1 day',"
            + " discard_reason = 'INC-3: consumers agreed' where event_id = ?", opened);
        assertThat(owner.queryForObject("select discard_requested_at > now() - interval '1 minute' and"
            + " discard_requested_by = ? from " + SCHEMA + ".outbox_event where event_id = ?", Boolean.class,
            PostgresTestDatabase.ownerUser(), opened)).isTrue();

        for (String change : List.of("park_reason = 'forged'", "aggregate_id = 'forged'",
                "event_type = 'CoreBanking.Account.Closed.v1'", "payload = '{}'::jsonb",
                "aggregate_version = 99", "occurred_at = now() - interval '1 day'")) {
            assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set " + change
                + " where event_id = ?", opened)).as(change)
                .hasMessageContaining("has a pending discard request");
        }
        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set parked_at = null"
            + " where event_id = ?", opened)).rootCause().hasMessageContaining("the service role cannot unpark");
        // Taking another row's id: the other row cannot be deleted (never published), nor this id changed.
        assertThatThrownBy(() -> jdbc.update("delete from " + SCHEMA + ".outbox_event where event_id = ?", otherOpened))
            .hasMessageContaining("was never published and cannot be deleted");
        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set event_id = ? where event_id = ?",
            UUID.randomUUID(), opened)).hasMessageContaining("the event id cannot change");
        // Leaving the outbox without an audit row.
        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set published_at = now(),"
            + " parked_at = null where event_id = ?", opened))
            .hasMessageContaining("it leaves the outbox only as an audited discard");

        Map<String, Object> row = owner.queryForMap("select park_reason, aggregate_id, event_type, published_at"
            + " from " + SCHEMA + ".outbox_event where event_id = ?", opened);
        assertThat(row).containsEntry("park_reason", "INC-3: test").containsEntry("aggregate_id", carol)
            .containsEntry("event_type", "CoreBanking.Account.Opened.v1").containsEntry("published_at", null);

        // An operator withdraws it with the replay; the trigger clears the reason and requester.
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = null, park_reason = null,"
            + " park_counted = false, last_error = null, discard_requested_at = null where event_id = ?", opened);
        assertThat(owner.queryForMap("select parked_at, discard_reason, discard_requested_by from " + SCHEMA
            + ".outbox_event where event_id = ?", opened)).containsEntry("parked_at", null)
            .containsEntry("discard_reason", null).containsEntry("discard_requested_by", null);
    }

    /**
     * DevSecOps on 4541ef6 (B1 residual): the audit must name the request that was carried out.
     * A request withdrawn and made again is audited as the second one; once the audit row exists
     * the row is already published, so the request can no longer be withdrawn or changed, and
     * the published row cannot change at all (S-b).
     */
    @Test
    void theAuditNamesTheRequestCarriedOutNotAWithdrawnOne() throws Exception {
        String dave = open("CUST-W", "CURRENT", "k-w");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        JdbcTemplate operator = PostgresTestDatabase.operator();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, dave);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-4: test'"
            + " where event_id = ?", opened);
        operator.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'FIRST' where event_id = ?", opened);
        operator.update("update " + SCHEMA + ".outbox_event set discard_requested_at = null where event_id = ?", opened);
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'SECOND by owner' where event_id = ?", opened);

        // What the relay runs, here as the runtime role directly.
        assertThat(outboxAudit(opened)).isZero();
        jdbc.update("insert into " + SCHEMA + ".outbox_discard_audit (event_id) values (?)", opened);

        assertThat(owner.queryForMap("select requested_by, discard_reason from " + SCHEMA
            + ".outbox_discard_audit where event_id = ?", opened))
            .containsEntry("requested_by", PostgresTestDatabase.ownerUser())
            .containsEntry("discard_reason", "SECOND by owner");
        assertThat(owner.queryForObject("select published_at is not null and parked_at is null from " + SCHEMA
            + ".outbox_event where event_id = ?", Boolean.class, opened)).isTrue();
        for (String change : List.of("discard_requested_at = null", "discard_reason = 'THIRD'")) {
            assertThatThrownBy(() -> operator.update("update " + SCHEMA + ".outbox_event set " + change
                + " where event_id = ?", opened)).as(change).hasMessageContaining("is already published");
        }
        for (JdbcTemplate role : List.of(jdbc, owner)) {
            for (String change : List.of("published_at = null", "published_at = now() + interval '1 year'",
                    "park_reason = 'rewritten'", "parked_at = now()")) {
                assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set " + change
                    + " where event_id = ?", opened)).as(change).hasMessageContaining("a published row cannot change");
            }
        }
    }

    /** The audit insert locks the requested row: a withdrawal in flight blocks it, then wins. */
    @Test
    void theAuditWaitsForAWithdrawalInFlightAndThenRefuses() throws Exception {
        String erin = open("CUST-L", "CURRENT", "k-l");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, erin);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-5: test'"
            + " where event_id = ?", opened);
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-5: agreed' where event_id = ?", opened);

        try (java.sql.Connection withdrawing = PostgresTestDatabase.ownerConnection();
             java.sql.Connection relaying = dataSource.getConnection()) {
            withdrawing.setAutoCommit(false);
            try (var st = withdrawing.prepareStatement("update " + SCHEMA
                    + ".outbox_event set discard_requested_at = null where event_id = ?")) {
                st.setObject(1, opened);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            relaying.setAutoCommit(false);
            try (var st = relaying.createStatement()) {
                st.execute("set local lock_timeout = '500ms'");
            }
            try (var st = relaying.prepareStatement("insert into " + SCHEMA + ".outbox_discard_audit (event_id) values (?)")) {
                st.setObject(1, opened);
                assertThatThrownBy(st::executeUpdate).hasMessageContaining("lock timeout");
            }
            relaying.rollback();
            withdrawing.commit();
        }
        assertThatThrownBy(() -> jdbc.update("insert into " + SCHEMA + ".outbox_discard_audit (event_id) values (?)",
            opened)).hasMessageContaining("has no pending discard request");
        assertThat(outboxAudit(opened)).isZero();
    }

    /** S-a: a parked row without an audited discard cannot be marked published, by any login. */
    @Test
    void aParkedRowLeavesTheOutboxOnlyAsAnAuditedDiscard() throws Exception {
        String frank = open("CUST-S", "CURRENT", "k-s");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, frank);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-6: test'"
            + " where event_id = ?", opened);
        // The runbook's operator grant has no published_at; the wide operator login shows the guard alone.
        assertThatThrownBy(() -> PostgresTestDatabase.operator().update("update " + SCHEMA + ".outbox_event"
            + " set published_at = now() where event_id = ?", opened)).rootCause().hasMessageContaining("permission denied");
        for (JdbcTemplate role : List.of(jdbc, owner, PostgresTestDatabase.operatorWide())) {
            for (String mark : List.of("published_at = now()", "published_at = now(), parked_at = null")) {
                assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set " + mark
                    + " where event_id = ?", opened)).as(mark)
                    .hasMessageContaining("it leaves the outbox only as an audited discard");
            }
        }
        assertThat(owner.queryForObject("select published_at is null from " + SCHEMA + ".outbox_event"
            + " where event_id = ?", Boolean.class, opened)).isTrue();
    }

    /**
     * DevSecOps on 8ec9013 (S-a, two statements): unparking and then publishing must not let a parked
     * row leave the outbox unsent. The service role cannot unpark; an operator's unpark is a replay,
     * and no login but the service role publishes.
     */
    @Test
    void aParkedRowCannotBeUnparkedAndThenMarkedPublished() throws Exception {
        String gina = open("CUST-U", "CURRENT", "k-u");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        JdbcTemplate operator = PostgresTestDatabase.operator();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, gina);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-7: test'"
            + " where event_id = ?", opened);

        assertThatThrownBy(() -> jdbc.update("update " + SCHEMA + ".outbox_event set parked_at = null"
            + " where event_id = ?", opened)).rootCause().hasMessageContaining("the service role cannot unpark");

        // The reviewer's sequence, in one transaction as a non-service login.
        try (java.sql.Connection connection = PostgresTestDatabase.ownerConnection()) {
            connection.setAutoCommit(false);
            try (var st = connection.prepareStatement("update " + SCHEMA
                    + ".outbox_event set parked_at = null where event_id = ?")) {
                st.setObject(1, opened);
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            try (var st = connection.prepareStatement("update " + SCHEMA
                    + ".outbox_event set published_at = now() where event_id = ?")) {
                st.setObject(1, opened);
                assertThatThrownBy(st::executeUpdate).hasMessageContaining("only the service role publishes");
            }
            connection.rollback();
        }

        // An operator's replay is allowed; publishing it is still the relay's job.
        operator.update("update " + SCHEMA + ".outbox_event set parked_at = null, park_reason = null,"
            + " park_counted = false, last_error = null where event_id = ?", opened);
        assertThatThrownBy(() -> operator.update("update " + SCHEMA + ".outbox_event set published_at = now()"
            + " where event_id = ?", opened)).rootCause().hasMessageContaining("permission denied");
        for (JdbcTemplate role : List.of(PostgresTestDatabase.operatorWide(), owner)) {
            assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set published_at = now()"
                + " where event_id = ?", opened)).rootCause().hasMessageContaining("only the service role publishes");
        }
        assertThat(owner.queryForObject("select published_at is null and parked_at is null from " + SCHEMA
            + ".outbox_event where event_id = ?", Boolean.class, opened)).isTrue();
        assertThat(outboxAudit(opened)).isZero();
    }

    /** DevSecOps on 8ec9013 (S1): a request an operator holds locked is skipped, not waited for. */
    @Test
    @Timeout(60)
    void theRelaySkipsADiscardAnOperatorIsChangingRightNow() throws Exception {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        List<UUID> requested = new ArrayList<>();
        for (String customer : List.of("CUST-H1", "CUST-H2")) {
            String account = open(customer, "CURRENT", "k-" + customer);
            UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, account);
            owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-8: test'"
                + " where event_id = ?", opened);
            owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
                + " discard_reason = 'INC-8: agreed' where event_id = ?", opened);
            requested.add(opened);
        }
        TransactionTemplate claim = new TransactionTemplate(transactionManager);

        try (java.sql.Connection holding = PostgresTestDatabase.ownerConnection()) {
            holding.setAutoCommit(false);
            try (var st = holding.prepareStatement("update " + SCHEMA
                    + ".outbox_event set discard_reason = 'INC-8: agreed, amended' where event_id = ?")) {
                st.setObject(1, requested.get(0));
                assertThat(st.executeUpdate()).isEqualTo(1);
            }
            // Would raise "lock timeout" if the relay waited for the operator.
            Integer discarded = claim.execute(status -> {
                jdbc.execute("set local lock_timeout = '2s'");
                return outbox.auditDiscardRequests();
            });
            assertThat(discarded).isEqualTo(1);
            assertThat(outboxAudit(requested.get(0))).isZero();
            assertThat(outboxAudit(requested.get(1))).isOne();
            holding.commit();
        }
        Integer later = claim.execute(status -> outbox.auditDiscardRequests());
        assertThat(later).isEqualTo(1);
        assertThat(owner.queryForObject("select discard_reason from " + SCHEMA + ".outbox_discard_audit"
            + " where event_id = ?", String.class, requested.get(0))).isEqualTo("INC-8: agreed, amended");
    }

    /** DevSecOps on 8ec9013 (low): a discarded event id cannot come back after the purge. */
    @Test
    void aDiscardedEventIdCannotBeReused() throws Exception {
        String hana = open("CUST-R", "CURRENT", "k-r");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, hana);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-9: test'"
            + " where event_id = ?", opened);
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-9: agreed' where event_id = ?", opened);
        jdbc.update("insert into " + SCHEMA + ".outbox_discard_audit (event_id) values (?)", opened);
        // The purge, as the runtime role.
        assertThat(jdbc.update("delete from " + SCHEMA + ".outbox_event where event_id = ?", opened)).isOne();
        assertThat(tombstone(opened)).isEqualTo("PURGED_DISCARDED");

        assertThatThrownBy(() -> jdbc.update("insert into " + SCHEMA + ".outbox_event (event_id, aggregate_type,"
            + " aggregate_id, aggregate_version, event_type, payload, correlation_id, occurred_at)"
            + " values (?, 'Account', ?, 1, 'CoreBanking.Account.Opened.v1', '{}', 'corr', now())", opened, hana))
            .hasMessageContaining("was discarded and cannot be reused");
    }

    /**
     * DevSecOps on ac7b25c, f11fdce and 21a239d: an operator parks a row while the relay is sending
     * it. The relay's single mark records sent_while_parked_at instead of publishing. The record is
     * write-once: no login forges, clears or changes it, a replay keeps it, a new park after the
     * replay still cannot be discarded, and the replayed row is published as usual.
     */
    @Test
    void aRowParkedDuringItsSendIsRecordedAndCannotBeDiscarded() throws Exception {
        String ivan = open("CUST-V", "CURRENT", "k-v");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        JdbcTemplate operator = PostgresTestDatabase.operator();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, ivan);
        List<String> sentIds = new ArrayList<>();
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(invocation -> {
            ProducerRecord<String, String> record = invocation.getArgument(0);
            String eventId = new String(record.headers().lastHeader("eventId").value());
            if (eventId.equals(opened.toString()) && !sentIds.contains(eventId)) {
                operator.update("update " + SCHEMA + ".outbox_event set parked_at = now(),"
                    + " park_reason = 'INC-10: parked during the send' where event_id = ?", opened);
            }
            sentIds.add(eventId);
            return CompletableFuture.completedFuture((SendResult<String, String>) null);
        });
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(outbox, kafka, new TransactionTemplate(transactionManager),
            new PostgresSessionRelayLock(dataSource, OutboxRelay.RELAY_LOCK_KEY), Clock.systemUTC(), 100,
            Duration.ofSeconds(5), Duration.ofDays(7), meters);

        relay.relayOnce();

        assertThat(sentIds).contains(opened.toString());
        Map<String, Object> row = owner.queryForMap("select published_at, parked_at, park_reason, last_error,"
            + " sent_while_parked_at from " + SCHEMA + ".outbox_event where event_id = ?", opened);
        assertThat(row.get("published_at")).isNull();
        assertThat(row.get("parked_at")).isNotNull();
        assertThat(row.get("sent_while_parked_at")).isNotNull();
        assertThat(row.get("park_reason")).isEqualTo("INC-10: parked during the send");
        assertThat((String) row.get("last_error")).startsWith("sent while parked: delivered to Kafka");
        assertThat(meters.counter("outbox.sent.while.parked").count()).isEqualTo(1.0);

        // The reviewer's attack on f11fdce: clear last_error, then request the discard.
        for (JdbcTemplate role : List.of(operator, jdbc)) {
            role.update("update " + SCHEMA + ".outbox_event set last_error = null where event_id = ?", opened);
        }
        assertThatThrownBy(() -> operator.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-10: discard' where event_id = ?", opened))
            .hasMessageContaining("was already sent to Kafka; replay it instead of discarding it");
        // The runbook's operator grant has no sent_while_parked_at; the wide login shows the guard alone.
        JdbcTemplate wide = PostgresTestDatabase.operatorWide();
        assertThatThrownBy(() -> operator.update("update " + SCHEMA + ".outbox_event set sent_while_parked_at = null"
            + " where event_id = ?", opened)).rootCause().hasMessageContaining("permission denied");
        for (JdbcTemplate role : List.of(jdbc, owner, wide)) {
            assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set sent_while_parked_at = null"
                + " where event_id = ?", opened)).hasMessageContaining("is write-once");
        }
        // Only the relay records a send: no other login can forge it on a parked row.
        UUID parked = otherParkedRow(owner);
        for (JdbcTemplate role : List.of(owner, wide)) {
            assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set sent_while_parked_at = now()"
                + " where event_id = ?", parked)).rootCause().hasMessageContaining("is set only by the relay");
        }
        // Not even inside a replay (the reviewer's attack on 21a239d, step 1).
        assertThatThrownBy(() -> wide.update("update " + SCHEMA + ".outbox_event set parked_at = null,"
            + " park_reason = null, sent_while_parked_at = null where event_id = ?", opened))
            .hasMessageContaining("is write-once");

        // The runbook replay keeps the record; a new park cannot be discarded either (steps 2 and 3).
        String replay = "update " + SCHEMA + ".outbox_event set parked_at = null, park_reason = null,"
            + " park_counted = false, last_error = null where event_id = ?";
        operator.update(replay, opened);
        operator.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-10: again'"
            + " where event_id = ?", opened);
        assertThatThrownBy(() -> operator.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-10: discard after replay' where event_id = ?", opened))
            .hasMessageContaining("was already sent to Kafka; replay it instead of discarding it");

        // Replayed again, the relay publishes it as usual, record kept (the reviewer's probe G).
        operator.update(replay, opened);
        relay.relayOnce();
        assertThat(owner.queryForObject("select published_at is not null and parked_at is null"
            + " and sent_while_parked_at is not null from " + SCHEMA + ".outbox_event where event_id = ?",
            Boolean.class, opened)).isTrue();
        assertThat(sentIds).filteredOn(opened.toString()::equals).hasSize(2);
        assertThat(outboxAudit(opened)).isZero();
    }

    /**
     * DevSecOps on bb0728b and 3459ced: the purge deletes published rows, but every id that may be in
     * Kafka keeps a tombstone (when the relay records a send while parked, and when the purge deletes
     * a published row), so the service role cannot insert it again, park it and have it discarded.
     * The tombstone is written by the schema owner's trigger; the service role can only read it.
     */
    @Test
    void aDeliveredEventIdCannotComeBackAfterThePurge() throws Exception {
        String lale = open("CUST-L", "CURRENT", "k-l");
        String mert = open("CUST-M2", "CURRENT", "k-m2");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        JdbcTemplate operator = PostgresTestDatabase.operator();
        String openedOf = "select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'";
        UUID parkedDuringSend = owner.queryForObject(openedOf, UUID.class, lale);
        UUID sentNormally = owner.queryForObject(openedOf, UUID.class, mert);
        operator.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-13: test'"
            + " where event_id = ?", parkedDuringSend);
        TransactionTemplate claim = new TransactionTemplate(transactionManager);
        Integer marked = claim.execute(status -> outbox.markSent(parkedDuringSend, Instant.now()));
        assertThat(marked).isOne();
        assertThat(owner.queryForObject("select count(*) from " + SCHEMA + ".outbox_event_tombstone t join "
            + SCHEMA + ".outbox_event o using (event_id) where event_id = ? and t.recorded_at = o.sent_while_parked_at"
            + " and t.cause = 'SENT_WHILE_PARKED'", Integer.class, parkedDuringSend)).isOne();

        operator.update("update " + SCHEMA + ".outbox_event set parked_at = null, park_reason = null,"
            + " park_counted = false, last_error = null where event_id = ?", parkedDuringSend);
        for (UUID eventId : List.of(parkedDuringSend, sentNormally)) {
            marked = claim.execute(status -> outbox.markSent(eventId, Instant.now()));
            assertThat(marked).isOne();
        }
        assertThat(tombstone(sentNormally)).isNull();
        // The purge, as the runtime role.
        for (UUID eventId : List.of(parkedDuringSend, sentNormally)) {
            assertThat(jdbc.update("delete from " + SCHEMA + ".outbox_event where event_id = ?", eventId)).isOne();
        }
        assertThat(tombstone(parkedDuringSend)).isEqualTo("SENT_WHILE_PARKED");
        assertThat(tombstone(sentNormally)).isEqualTo("PURGED");

        for (UUID eventId : List.of(parkedDuringSend, sentNormally)) {
            assertThatThrownBy(() -> jdbc.update("insert into " + SCHEMA + ".outbox_event (event_id, aggregate_type,"
                + " aggregate_id, aggregate_version, event_type, payload, correlation_id, occurred_at)"
                + " values (?, 'Account', ?, 1, 'CoreBanking.Account.Opened.v1', '{}', 'corr', now())", eventId, lale))
                .hasMessageContaining("was already sent to Kafka and cannot be reused");
        }
        // The service role can neither forge, pre-empt nor remove a tombstone.
        UUID unsent = UUID.randomUUID();
        for (String change : List.of(
                "insert into " + SCHEMA + ".outbox_event_tombstone values ('" + unsent + "', now(), 'PURGED')",
                "delete from " + SCHEMA + ".outbox_event_tombstone where event_id = '" + sentNormally + "'",
                "update " + SCHEMA + ".outbox_event_tombstone set event_id = gen_random_uuid()"
                    + " where event_id = '" + sentNormally + "'")) {
            assertThatThrownBy(() -> jdbc.update(change)).as(change).rootCause().hasMessageContaining("permission denied");
        }
        // SF1: the column comment says what V7 made true.
        assertThat(owner.queryForObject("select col_description('" + SCHEMA + ".outbox_event'::regclass,"
            + " (select attnum from pg_attribute where attrelid = '" + SCHEMA + ".outbox_event'::regclass"
            + " and attname = 'sent_while_parked_at'))", String.class)).contains("write-once, never cleared");
    }

    /**
     * DevSecOps on cc87121 (B1): deleting a sent row and inserting its id again in one statement
     * (a writable CTE, or MERGE) must not bring the id back unpublished. The tombstone is written
     * before the row goes, so the insert later in the same statement finds it.
     */
    @Test
    void aSentEventIdCannotComeBackInTheStatementThatDeletesIt() throws Exception {
        JdbcTemplate owner = PostgresTestDatabase.owner();
        String columns = "event_id, aggregate_type, aggregate_id, aggregate_version, event_type, payload,"
            + " correlation_id, occurred_at";
        Map<String, String> attempts = Map.of(
            "CUST-B1", "with d as (delete from " + SCHEMA + ".outbox_event where event_id = ? and published_at"
                + " is not null returning " + columns + ") insert into " + SCHEMA + ".outbox_event (" + columns
                + ") select " + columns + " from d",
            "CUST-B2", "merge into " + SCHEMA + ".outbox_event o using (select o2.*, s.n from " + SCHEMA
                + ".outbox_event o2 cross join (values (1), (2)) s(n) where o2.event_id = ?) x"
                + " on o.event_id = x.event_id and x.n = 1"
                + " when matched then delete"
                + " when not matched then insert (" + columns + ") values (x.event_id, x.aggregate_type,"
                + " x.aggregate_id, x.aggregate_version, x.event_type, x.payload, x.correlation_id, x.occurred_at)");
        for (Map.Entry<String, String> attempt : attempts.entrySet()) {
            String account = open(attempt.getKey(), "CURRENT", "k-" + attempt.getKey());
            UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
                + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, account);
            Integer marked = new TransactionTemplate(transactionManager)
                .execute(status -> outbox.markSent(opened, Instant.now()));
            assertThat(marked).isOne();

            assertThatThrownBy(() -> jdbc.update(attempt.getValue(), opened)).as(attempt.getKey())
                .hasMessageContaining("was already sent to Kafka and cannot be reused");
            assertThat(owner.queryForObject("select published_at is not null from " + SCHEMA
                + ".outbox_event where event_id = ?", Boolean.class, opened)).as(attempt.getKey()).isTrue();
        }
    }

    private String tombstone(UUID eventId) {
        List<String> causes = PostgresTestDatabase.owner().queryForList("select cause from " + SCHEMA
            + ".outbox_event_tombstone where event_id = ?", String.class, eventId);
        return causes.isEmpty() ? null : causes.get(0);
    }

    /** DevSecOps on ac7b25c (should-fix 1): a request made before the note never becomes a discard. */
    @Test
    void aDiscardRequestedBeforeTheSendRecordIsSkippedAndShowsAsWaiting() throws Exception {
        String jana = open("CUST-N", "CURRENT", "k-n");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, jana);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-11: test'"
            + " where event_id = ?", opened);
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = now(),"
            + " discard_reason = 'INC-11: agreed' where event_id = ?", opened);
        assertThat(outbox.oldestPendingDiscardRequestedAt()).isNotNull();
        // The relay's mark when the send it was making turns out to be this row's.
        TransactionTemplate claim = new TransactionTemplate(transactionManager);
        Boolean parkedDuringSend = claim.execute(status ->
            outbox.markSent(opened, java.time.Instant.now()) == 1 ? outbox.isUnpublished(opened) : null);
        assertThat(parkedDuringSend).isTrue();

        Integer discarded = claim.execute(status -> outbox.auditDiscardRequests());
        assertThat(discarded).isZero();
        assertThatThrownBy(() -> jdbc.update("insert into " + SCHEMA + ".outbox_discard_audit (event_id) values (?)",
            opened)).hasMessageContaining("has no pending discard request (or was already sent)");
        assertThat(outbox.oldestPendingDiscardRequestedAt()).isNotNull();
        owner.update("update " + SCHEMA + ".outbox_event set discard_requested_at = null where event_id = ?", opened);
        assertThat(outbox.oldestPendingDiscardRequestedAt()).isNull();
    }

    /** DevSecOps on ac7b25c (low): the event id never changes, for any row and any login. */
    @Test
    void theEventIdNeverChanges() throws Exception {
        String kemal = open("CUST-E", "CURRENT", "k-e");
        JdbcTemplate owner = PostgresTestDatabase.owner();
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, kemal);
        assertThatThrownBy(() -> PostgresTestDatabase.operator().update("update " + SCHEMA + ".outbox_event"
            + " set event_id = ? where event_id = ?", UUID.randomUUID(), opened))
            .rootCause().hasMessageContaining("permission denied");
        for (JdbcTemplate role : List.of(jdbc, owner, PostgresTestDatabase.operatorWide())) {
            assertThatThrownBy(() -> role.update("update " + SCHEMA + ".outbox_event set event_id = ? where event_id = ?",
                UUID.randomUUID(), opened)).hasMessageContaining("the event id cannot change");
        }
    }

    /** A freshly parked row of a new account, for attempts that must not touch the row under test. */
    private UUID otherParkedRow(JdbcTemplate owner) throws Exception {
        String account = open(null, "INTERNAL", "k-other-" + UUID.randomUUID());
        UUID opened = owner.queryForObject("select event_id from " + SCHEMA + ".outbox_event"
            + " where aggregate_id = ? and event_type = 'CoreBanking.Account.Opened.v1'", UUID.class, account);
        owner.update("update " + SCHEMA + ".outbox_event set parked_at = now(), park_reason = 'INC-12: test'"
            + " where event_id = ?", opened);
        return opened;
    }

    private int outboxAudit(UUID eventId) {
        return PostgresTestDatabase.owner().queryForObject("select count(*) from " + SCHEMA
            + ".outbox_discard_audit where event_id = ?", Integer.class, eventId);
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
