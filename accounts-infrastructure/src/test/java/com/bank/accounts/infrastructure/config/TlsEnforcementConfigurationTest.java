package com.bank.accounts.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The assertion runs as a bean factory post-processor: before any other bean
 * exists, so a misconfigured pod never opens a database or Kafka connection.
 */
class TlsEnforcementConfigurationTest {

    @Test
    void theContextRefusesToStartBeforeAnyOtherBeanIsCreated() {
        Canary.created = false;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment("jdbc:postgresql://db:5432/d?sslmode=require", "SASL_SSL"));
            context.register(TlsEnforcementConfiguration.class, Canary.class);

            assertThatThrownBy(context::refresh)
                .satisfies(e -> assertThat(rootCause(e))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("spring.datasource.url"));
            assertThat(Canary.created).as("no other bean was created").isFalse();
        }
    }

    @Test
    void aCompliantEnvironmentStarts() {
        Canary.created = false;
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.setEnvironment(environment(
                "jdbc:postgresql://db:5432/d?sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem", "SASL_SSL"));
            context.register(TlsEnforcementConfiguration.class, Canary.class);

            context.refresh();

            assertThat(Canary.created).isTrue();
        }
    }

    private static MockEnvironment environment(String datasourceUrl, String kafkaProtocol) {
        return new MockEnvironment()
            .withProperty("fintechbankx.tls.enforce", "true")
            .withProperty("spring.datasource.url", datasourceUrl)
            .withProperty("spring.kafka.bootstrap-servers", "kafka:9098")
            .withProperty("spring.kafka.security.protocol", kafkaProtocol);
    }

    private static Throwable rootCause(Throwable e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root;
    }

    @Configuration
    static class Canary {
        static boolean created;

        @Bean
        Object canary() {
            created = true;
            return new Object();
        }
    }
}
