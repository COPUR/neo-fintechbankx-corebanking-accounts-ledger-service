package com.bank.accounts.config;

import com.bank.accounts.AccountsLedgerApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Governance round 3: fintechbankx.tls.enforce is true in application.yml, only
 * the local profile sets it false (test configuration does so per test), the
 * Kafka profiles leave it alone, and the assembled service refuses to start on
 * a datasource URL below sslmode=verify-full before it opens any connection.
 */
class TlsEnforcementProfilesTest {

    @Test
    void enforcementIsOnByDefault() throws Exception {
        assertThat(load("application.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo(true);
    }

    @Test
    void onlyTheLocalProfileRelaxesIt() throws Exception {
        assertThat(load("application-local.yml").getProperty("fintechbankx.tls.enforce")).isEqualTo(false);
        assertThat(load("application-kafka-msk.yml").getProperty("fintechbankx.tls.enforce")).isNull();
        assertThat(load("application-kafka-strimzi.yml").getProperty("fintechbankx.tls.enforce")).isNull();
    }

    @Test
    void theServiceRefusesToStartOnSslmodeRequire() {
        SpringApplicationBuilder service = new SpringApplicationBuilder(AccountsLedgerApplication.class)
            .web(WebApplicationType.NONE);
        // Command-line arguments, as a pod's environment would: they outrank application.yml's ${DB_URL:...}.
        String[] podLikeSettings = {
            "--spring.datasource.url=jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?sslmode=require",
            "--spring.kafka.security.protocol=SASL_SSL",
            "--spring.kafka.bootstrap-servers=b-1.msk.internal:9098",
            "--accounts.outbox.relay.enabled=false"};

        assertThatThrownBy(() -> service.run(podLikeSettings))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fintechbankx.tls.enforce=true")
            .hasMessageContaining("spring.datasource.url must carry sslmode=verify-full, found sslmode=require");
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }
}
