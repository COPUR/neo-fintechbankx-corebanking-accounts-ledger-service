package com.bank.accounts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Governance round 3, hardened in round 6 (PR #14 review): the service fails fast
 * at startup unless every datasource URL a pool can use verifies the server
 * certificate (sslmode=verify-full, read the way PgJDBC reads the query string)
 * and the Kafka client, when one is configured, talks SASL_SSL (SSL on the
 * Strimzi mutual-TLS listener) once Spring Kafka has merged its settings. Only
 * explicit local or test configuration turns the check off.
 */
class StartupTlsAssertionTest {

    private static final String VERIFY_FULL =
        "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem";
    private static final String REQUIRE = "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?sslmode=require&user=app";

    @Test
    void failsFastWhenTheDatasourceUrlOnlyRequiresSsl() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(REQUIRE, "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fintechbankx.tls.enforce")
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("sslmode=verify-full")
            .hasMessageContaining("found sslmode=require")
            // Names the setting, never the URL (it may carry credentials).
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("db.internal").doesNotContain("user=app"));
    }

    @Test
    void aDatasourceUrlWithoutAnySslmodeFails() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env("jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod", "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("found no sslmode");
    }

    /** PgJDBC takes the last occurrence; a later sslmode=disable would silently win over verify-full. */
    @Test
    void aSecondSslmodeFailsEvenWhenTheFirstIsVerifyFull() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(VERIFY_FULL + "&sslmode=disable", "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("sslmode exactly once")
            .hasMessageContaining("found 2");
    }

    /** PgJDBC keys are case-sensitive: SSLMODE is ignored and the driver falls back to sslmode=prefer. */
    @Test
    void anUpperCaseSslmodeIsNotReadAsVerifyFull() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(
                "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?SSLMODE=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem",
                "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("lower case")
            .hasMessageContaining("SSLMODE");
    }

    /** The value of another parameter is not a parameter: only the key before the first '=' counts. */
    @Test
    void aVerifyFullInsideAnotherParameterValueDoesNotCount() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(
                "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?sslmode=require&applicationName=sslmode=verify-full", "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("found sslmode=require");
    }

    @ParameterizedTest
    @ValueSource(strings = {"sslfactory=org.postgresql.ssl.NonValidatingFactory", "sslfactoryarg=classpath:x",
        "sslhostnameverifier=x.Y", "sslpasswordcallback=x.Y", "service=prod"})
    void aParameterThatCanBypassVerificationIsRefused(String parameter) {
        String key = parameter.substring(0, parameter.indexOf('='));

        assertThatThrownBy(() -> StartupTlsAssertion.check(env(VERIFY_FULL + "&" + parameter, "SASL_SSL")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.url")
            .hasMessageContaining("must not set " + key)
            .satisfies(e -> assertThat(e.getMessage()).doesNotContain("db.internal"));
    }

    @Test
    void verifyFullAmongOtherParametersPasses() {
        assertThatCode(() -> StartupTlsAssertion.check(env(
                "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod?a=1&sslmode=verify-full&b=2&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem",
                "SASL_SSL")))
            .doesNotThrowAnyException();
    }

    /** Hikari's own jdbc-url outranks spring.datasource.url; a pool built from it must be verify-full too. */
    @Test
    void theHikariJdbcUrlIsCheckedAsWell() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.datasource.hikari.jdbc-url", REQUIRE);

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.hikari.jdbc-url")
            .hasMessageContaining("found sslmode=require");
    }

    @Test
    void theFlywayUrlIsCheckedWhenSet() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.flyway.url", "jdbc:postgresql://db.internal:5432/db_acc_accounts_ledger_prod");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.flyway.url")
            .hasMessageContaining("found no sslmode");
    }

    /** Hikari passes data-source-properties to the driver; an ssl* property there can undo the URL. */
    @ParameterizedTest
    @ValueSource(strings = {"sslmode", "sslfactory", "sslrootcert", "sslhostnameverifier", "SSLMODE"})
    void aHikariDataSourcePropertyThatTouchesSslIsRefused(String property) {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.datasource.hikari.data-source-properties." + property, "x");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.datasource.hikari.data-source-properties." + property)
            .hasMessageContaining("ssl");
    }

    @Test
    void aHikariDataSourcePropertyUnrelatedToSslPasses() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.datasource.hikari.data-source-properties.ApplicationName", "svc-pay");

        assertThatCode(() -> StartupTlsAssertion.check(env)).doesNotThrowAnyException();
    }

    @Test
    void failsFastWhenTheKafkaClientIsPlaintext() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(VERIFY_FULL, "PLAINTEXT")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("fintechbankx.tls.enforce")
            .hasMessageContaining("spring.kafka.security.protocol")
            .hasMessageContaining("SASL_SSL")
            .hasMessageContaining("found PLAINTEXT");
    }

    @Test
    void passesOnVerifyFullPlusSaslSsl() {
        assertThatCode(() -> StartupTlsAssertion.check(env(VERIFY_FULL, "SASL_SSL"))).doesNotThrowAnyException();
    }

    @Test
    void theStrimziMutualTlsListenerPassesToo() {
        assertThatCode(() -> StartupTlsAssertion.check(env(VERIFY_FULL, "SSL"))).doesNotThrowAnyException();
    }

    @Test
    void saslPlaintextIsNotTls() {
        assertThatThrownBy(() -> StartupTlsAssertion.check(env(VERIFY_FULL, "SASL_PLAINTEXT")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("found SASL_PLAINTEXT");
    }

    /** Spring Kafka merges producer-level and properties-level settings over spring.kafka.security.protocol. */
    @Test
    void aProducerPropertiesOverrideToPlaintextFails() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.kafka.producer.properties.security.protocol", "PLAINTEXT");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.kafka.producer.properties.security.protocol")
            .hasMessageContaining("found PLAINTEXT");
    }

    @Test
    void aCommonPropertiesOverrideToPlaintextFails() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.kafka.properties.security.protocol", "PLAINTEXT");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.kafka.properties.security.protocol")
            .hasMessageContaining("found PLAINTEXT");
    }

    @Test
    void aProducerLevelOverrideToPlaintextFails() {
        MockEnvironment env = env(VERIFY_FULL, "SASL_SSL")
            .withProperty("spring.kafka.producer.security.protocol", "PLAINTEXT");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("spring.kafka.producer.security.protocol")
            .hasMessageContaining("found PLAINTEXT");
    }

    /** What counts is the protocol Spring Kafka hands the producer, after its own precedence rules. */
    @Test
    void theEffectiveProducerProtocolIsTheOneSpringKafkaBuilds() {
        MockEnvironment env = env(VERIFY_FULL, "PLAINTEXT")
            .withProperty("spring.kafka.producer.properties.security.protocol", "SASL_SSL");

        assertThatCode(() -> StartupTlsAssertion.check(env)).doesNotThrowAnyException();
    }

    @Test
    void noKafkaClientMeansNoKafkaCheck() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", VERIFY_FULL)
            .withProperty("spring.kafka.bootstrap-servers", "")
            .withProperty("spring.kafka.security.protocol", "PLAINTEXT");

        assertThatCode(() -> StartupTlsAssertion.check(env)).doesNotThrowAnyException();
    }

    @Test
    void enforceFalseSkipsBothChecks() {
        MockEnvironment env = env(REQUIRE, "PLAINTEXT").withProperty("fintechbankx.tls.enforce", "false");

        assertThatCode(() -> StartupTlsAssertion.check(env)).doesNotThrowAnyException();
    }

    @Test
    void enforcementIsOnWhenThePropertyIsAbsent() {
        MockEnvironment env = new MockEnvironment()
            .withProperty("spring.datasource.url", REQUIRE)
            .withProperty("spring.kafka.bootstrap-servers", "kafka:9098")
            .withProperty("spring.kafka.security.protocol", "SASL_SSL");

        assertThatThrownBy(() -> StartupTlsAssertion.check(env)).isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment env(String datasourceUrl, String kafkaProtocol) {
        return new MockEnvironment()
            .withProperty("fintechbankx.tls.enforce", "true")
            .withProperty("spring.datasource.url", datasourceUrl)
            .withProperty("spring.kafka.bootstrap-servers", "b-1.msk.internal:9098")
            .withProperty("spring.kafka.security.protocol", kafkaProtocol);
    }
}
