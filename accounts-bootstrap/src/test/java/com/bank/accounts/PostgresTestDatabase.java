package com.bank.accounts;

import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * PostgreSQL for integration tests. CI provides one through TEST_DB_URL
 * (a service container in required-gates.yml) and the tests fail there
 * without it; locally either set TEST_DB_URL or have Docker running for
 * Testcontainers, otherwise the integration tests are skipped.
 *
 * Runs the service with the two roles of production: Flyway as the schema
 * owner (the database's test user, through DB_MIGRATION_USERNAME / _PASSWORD,
 * in-process here, a Helm hook Job when deployed) and the application as a
 * separate runtime role (DB_USERNAME) that has only the grants of V3. The
 * owner needs CREATEROLE to create that role.
 */
final class PostgresTestDatabase {

    static final String RUNTIME_ROLE = "accounts_runtime_it";
    static final String RUNTIME_PASSWORD = "accounts_runtime_it";

    private static PostgreSQLContainer<?> container;
    private static String url;
    private static String ownerUser;
    private static String ownerPassword;

    private PostgresTestDatabase() {
    }

    /**
     * Call from a static @BeforeAll. In CI (CI=true) a missing TEST_DB_URL
     * fails the build, so the integration tests can never be skipped silently;
     * elsewhere the class is skipped without a database.
     */
    static void assumeAvailable() {
        requireInCi(System.getenv("CI"), System.getenv("TEST_DB_URL"));
        Assumptions.assumeTrue(hasExternalDatabase() || DockerClientFactory.instance().isDockerAvailable(),
            "Set TEST_DB_URL or start Docker to run PostgreSQL integration tests");
    }

    static void requireInCi(String ci, String testDbUrl) {
        if ("true".equalsIgnoreCase(ci) && (testDbUrl == null || testDbUrl.isBlank())) {
            throw new IllegalStateException(
                "CI=true but TEST_DB_URL is not set: integration tests must run against PostgreSQL in CI");
        }
    }

    private static boolean hasExternalDatabase() {
        String value = System.getenv("TEST_DB_URL");
        return value != null && !value.isBlank();
    }

    static synchronized void register(DynamicPropertyRegistry registry) {
        start();
        registry.add("spring.datasource.url", () -> url);
        // Runtime role: what the pods connect as.
        registry.add("DB_USERNAME", () -> RUNTIME_ROLE);
        registry.add("spring.datasource.password", () -> RUNTIME_PASSWORD);
        // Schema owner: what Flyway connects as.
        registry.add("DB_MIGRATION_USERNAME", () -> ownerUser);
        registry.add("DB_MIGRATION_PASSWORD", () -> ownerPassword);
    }

    static synchronized String url() {
        start();
        return url;
    }

    static synchronized String ownerUser() {
        start();
        return ownerUser;
    }

    static synchronized String ownerPassword() {
        start();
        return ownerPassword;
    }

    /** The schema owner's connection, for test set-up the runtime role may not do. */
    static synchronized JdbcTemplate owner() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword));
    }

    /** A login that is neither owner nor runtime role, like an operator's personal login. */
    static final String OPERATOR_ROLE = "accounts_operator_it";

    /** Like the operator, but with table-wide UPDATE: shows the guard holds without the column grant. */
    static final String OPERATOR_WIDE_ROLE = "accounts_operator_wide_it";

    /**
     * An operator's personal login with the runbook's grant (docs/RUNBOOK.md): read the outbox and
     * its audit, update only the park, replay and discard-request columns. Created and granted on
     * first use, after Flyway built the schema.
     */
    static synchronized JdbcTemplate operator() {
        JdbcTemplate owner = operatorLogin(OPERATOR_ROLE);
        owner.execute("revoke update on sc_acc_accounts_ledger.outbox_event from " + OPERATOR_ROLE);
        owner.execute("grant select on sc_acc_accounts_ledger.outbox_event, sc_acc_accounts_ledger.outbox_discard_audit to "
            + OPERATOR_ROLE);
        owner.execute("grant update (parked_at, park_reason, park_counted, last_error, discard_requested_at, discard_reason)"
            + " on sc_acc_accounts_ledger.outbox_event to " + OPERATOR_ROLE);
        return new JdbcTemplate(new DriverManagerDataSource(url, OPERATOR_ROLE, RUNTIME_PASSWORD));
    }

    /**
     * An operator login with table-wide UPDATE on the outbox, wider than the runbook's grant, for
     * the ITs that show the guard trigger alone refuses what the column grant would already stop.
     */
    static synchronized JdbcTemplate operatorWide() {
        JdbcTemplate owner = operatorLogin(OPERATOR_WIDE_ROLE);
        owner.execute("grant select, update on sc_acc_accounts_ledger.outbox_event to " + OPERATOR_WIDE_ROLE);
        owner.execute("grant select on sc_acc_accounts_ledger.outbox_discard_audit to " + OPERATOR_WIDE_ROLE);
        return new JdbcTemplate(new DriverManagerDataSource(url, OPERATOR_WIDE_ROLE, RUNTIME_PASSWORD));
    }

    private static JdbcTemplate operatorLogin(String role) {
        JdbcTemplate owner = owner();
        owner.execute("""
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = '%1$s') then
                    create role %1$s login password '%2$s';
                end if;
            end $$
            """.formatted(role, RUNTIME_PASSWORD));
        owner.execute("grant usage on schema sc_acc_accounts_ledger to " + role);
        return owner;
    }

    /** A raw connection as the schema owner, for tests that hold a transaction open. */
    static synchronized java.sql.Connection ownerConnection() throws java.sql.SQLException {
        start();
        return java.sql.DriverManager.getConnection(url, ownerUser, ownerPassword);
    }

    /** A plain connection as the runtime role, outside the application. */
    static synchronized JdbcTemplate runtime() {
        start();
        return new JdbcTemplate(new DriverManagerDataSource(url, RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static void start() {
        if (url != null) {
            return;
        }
        String external = System.getenv("TEST_DB_URL");
        if (external != null && !external.isBlank()) {
            url = external;
            ownerUser = env("TEST_DB_USERNAME", "accounts_test");
            // Empty when the CI database trusts its clients (POSTGRES_HOST_AUTH_METHOD=trust).
            ownerPassword = env("TEST_DB_PASSWORD");
        } else {
            container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("db_acc_accounts_ledger_test")
                .withUsername("accounts_test")
                .withPassword("accounts_test");
            container.start();
            url = container.getJdbcUrl();
            ownerUser = container.getUsername();
            ownerPassword = container.getPassword();
        }
        new JdbcTemplate(new DriverManagerDataSource(url, ownerUser, ownerPassword)).execute("""
            do $$
            begin
                if not exists (select 1 from pg_roles where rolname = '%1$s') then
                    create role %1$s login password '%2$s';
                end if;
            end $$
            """.formatted(RUNTIME_ROLE, RUNTIME_PASSWORD));
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value;
    }
}
