package com.bank.accounts;

import com.bank.accounts.infrastructure.config.TlsEnforcementConfiguration;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;

import java.util.Arrays;

/**
 * svc-acc-accounts-ledger: the core-banking accounts ledger (context acc),
 * extracted from the AmanahFi accounts context of
 * enterprise-loan-management-system. It owns accounts, balances and the
 * double-entry journal; payments, mandates, bulk and loan services read
 * accounts and post entries here. It calls no other service.
 *
 * With the first argument "migrate" the image runs only the Flyway migrations
 * (as the schema owner, DB_MIGRATION_USERNAME / _PASSWORD) and exits: the Helm
 * pre-install/pre-upgrade Job. The service pods never hold the owner
 * credential and run with spring.flyway.enabled=false. The migrate context runs
 * the startup TLS assertion too (TlsEnforcementConfiguration, imported by hand
 * because there is no component scan) and declares no Kafka client, so only its
 * datasource URL is checked.
 */
@SpringBootApplication
public class AccountsLedgerApplication {

    static final String MIGRATE = "migrate";

    public static void main(String[] args) {
        if (args.length > 0 && MIGRATE.equals(args[0])) {
            System.exit(run(args));
        }
        SpringApplication.run(AccountsLedgerApplication.class, args);
    }

    /**
     * Runs the migrations and returns the process exit code (0 on success).
     * Only the DataSource and Flyway are configured: no web server, Kafka,
     * security or application beans.
     */
    static int run(String... args) {
        SpringApplication migration = new SpringApplication(DatabaseMigration.class);
        migration.setWebApplicationType(WebApplicationType.NONE);
        migration.setBannerMode(Banner.Mode.OFF);
        try (ConfigurableApplicationContext context = migration.run(migrationArgs(args))) {
            return SpringApplication.exit(context);
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /**
     * The migrate context's arguments: the caller's without the leading "migrate",
     * Flyway on, and no Kafka client (the Job produces nothing, so the TLS
     * assertion checks the datasource URL only).
     */
    static String[] migrationArgs(String... args) {
        String[] rest = Arrays.copyOfRange(args, MIGRATE.equals(args.length > 0 ? args[0] : null) ? 1 : 0, args.length);
        String[] withFlyway = Arrays.copyOf(rest, rest.length + 2);
        withFlyway[rest.length] = "--spring.flyway.enabled=true";
        withFlyway[rest.length + 1] = "--spring.kafka.bootstrap-servers=";
        return withFlyway;
    }

    /**
     * Not a component (no stereotype), so the service's component scan never picks it
     * up. Imports the TLS assertion explicitly: it runs as a bean factory post-processor,
     * before the DataSource and Flyway exist.
     */
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class, FlywayAutoConfiguration.class})
    @Import(TlsEnforcementConfiguration.class)
    static class DatabaseMigration {
    }
}
