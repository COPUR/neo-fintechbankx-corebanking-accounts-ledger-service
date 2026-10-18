package com.bank.accounts;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Round 6: the migration Job context ("migrate", {@code DatabaseMigration}, no
 * component scan) runs the startup TLS assertion too, before the DataSource or
 * Flyway is created, and declares no Kafka client, so only its database URL is
 * checked. No database is needed: the context must stop before it connects.
 */
class MigrationContextTlsTest {

    @Test
    void theMigrateContextRefusesToStartOnSslmodeRequireBeforeConnecting() {
        SpringApplication migration = new SpringApplication(AccountsLedgerApplication.DatabaseMigration.class);
        migration.setWebApplicationType(WebApplicationType.NONE);
        migration.setBannerMode(Banner.Mode.OFF);

        assertThatThrownBy(() -> migration.run(
                "--spring.datasource.url=jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?sslmode=require",
                "--spring.kafka.bootstrap-servers=",
                "--spring.flyway.enabled=true"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fintechbankx.tls.enforce=true")
            .hasMessageContaining("spring.datasource.url must carry sslmode=verify-full, found sslmode=require")
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("db.internal"));
    }

    /** The Job has no Kafka client: its run says so, and still asserts the datasource URL. */
    @Test
    void theMigrateRunDeclaresNoKafkaClient() {
        assertThat(AccountsLedgerApplication.migrationArgs("migrate", "--spring.datasource.url=jdbc:x"))
            .containsExactly("--spring.datasource.url=jdbc:x", "--spring.flyway.enabled=true",
                "--spring.kafka.bootstrap-servers=");
    }
}
