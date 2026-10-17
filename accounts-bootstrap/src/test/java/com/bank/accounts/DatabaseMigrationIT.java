package com.bank.accounts;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The deployed migration step: the Helm pre-install/pre-upgrade Job runs the
 * image with the argument "migrate". It runs Flyway as the schema owner,
 * grants the runtime role, starts no web server, no Kafka and no security,
 * and exits 0. Runs against a scratch schema so the shared one is untouched.
 * The test database has no TLS, so these runs switch the startup TLS assertion
 * off explicitly, as test configuration may (MigrationContextTlsTest covers it).
 */
class DatabaseMigrationIT {

    private static final String SCHEMA = "sc_acc_migration_job_it";

    @BeforeAll
    static void requireDatabase() {
        PostgresTestDatabase.assumeAvailable();
    }

    @AfterEach
    void dropScratchSchema() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
    }

    @Test
    void migrateRunsFlywayAsTheOwnerGrantsTheRuntimeRoleAndExits() {
        PostgresTestDatabase.owner().execute("drop schema if exists " + SCHEMA + " cascade");
        PostgresTestDatabase.runtime().queryForObject("select 1", Integer.class); // creates the runtime role

        int exitCode = AccountsLedgerApplication.run(
            "migrate",
            "--fintechbankx.tls.enforce=false",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            "--DB_MIGRATION_USERNAME=" + PostgresTestDatabase.ownerUser(),
            "--DB_MIGRATION_PASSWORD=" + PostgresTestDatabase.ownerPassword(),
            "--spring.flyway.schemas=" + SCHEMA,
            "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isZero();
        JdbcTemplate owner = PostgresTestDatabase.owner();
        assertThat(owner.queryForObject(
            "select count(*) from " + SCHEMA + ".flyway_schema_history where success", Integer.class)).isGreaterThanOrEqualTo(3);
        assertThat(owner.queryForObject(
            "select tableowner from pg_tables where schemaname = ? and tablename = 'account'", String.class, SCHEMA))
            .isEqualTo(PostgresTestDatabase.ownerUser());
        assertThat(owner.queryForObject(
            "select has_table_privilege(?, ?, 'INSERT')", Boolean.class,
            PostgresTestDatabase.RUNTIME_ROLE, SCHEMA + ".account")).isTrue();
        assertThat(owner.queryForObject(
            "select has_schema_privilege(?, ?, 'CREATE')", Boolean.class,
            PostgresTestDatabase.RUNTIME_ROLE, SCHEMA)).isFalse();
    }

    @Test
    void migrateFailsWithANonZeroExitCodeWhenTheDatabaseRefuses() {
        int exitCode = AccountsLedgerApplication.run(
            "migrate",
            "--fintechbankx.tls.enforce=false",
            "--spring.datasource.url=" + PostgresTestDatabase.url(),
            "--DB_USERNAME=" + PostgresTestDatabase.RUNTIME_ROLE,
            // A role that does not exist is refused whatever the server's auth method (trust in CI).
            "--DB_MIGRATION_USERNAME=migration_role_that_does_not_exist",
            "--spring.flyway.schemas=" + SCHEMA,
            "--spring.flyway.default-schema=" + SCHEMA);

        assertThat(exitCode).isNotZero();
    }
}
