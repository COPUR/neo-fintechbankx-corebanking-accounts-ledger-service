package com.bank.accounts.config;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka client conventions: the kafka-msk profile (Helm default) uses IAM auth
 * with classes on the runtime classpath, kafka-strimzi uses mutual TLS with PEM
 * material, the service never creates topics, and the producer settings are
 * ones Kafka accepts.
 */
class KafkaProfilesConfigurationTest {

    @Test
    void kafkaMskProfileUsesIamAuthWithClassesOnTheClasspath() throws Exception {
        PropertySource<?> msk = load("application-kafka-msk.yml");

        assertThat(msk.getProperty("spring.kafka.security.protocol")).hasToString("SASL_SSL");
        assertThat(msk.getProperty("spring.kafka.properties.sasl.mechanism")).hasToString("AWS_MSK_IAM");
        String handler = msk.getProperty("spring.kafka.properties.sasl.client.callback.handler.class").toString();
        String jaas = msk.getProperty("spring.kafka.properties.sasl.jaas.config").toString();
        assertThat(Class.forName(handler)).isNotNull();
        assertThat(Class.forName(jaas.substring(0, jaas.indexOf(' ')))).isNotNull();
    }

    @Test
    void kafkaStrimziProfileUsesMutualTlsWithPemMaterial() throws Exception {
        PropertySource<?> strimzi = load("application-kafka-strimzi.yml");

        assertThat(strimzi.getProperty("spring.kafka.security.protocol")).hasToString("SSL");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-type")).hasToString("PEM");
        assertThat(strimzi.getProperty("spring.kafka.ssl.trust-store-type")).hasToString("PEM");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-certificate-chain")).hasToString("${KAFKA_TLS_CERT}");
        assertThat(strimzi.getProperty("spring.kafka.ssl.key-store-key")).hasToString("${KAFKA_TLS_KEY}");
        assertThat(strimzi.getProperty("spring.kafka.ssl.trust-store-certificates")).hasToString("${KAFKA_TLS_CA}");
    }

    @Test
    void baseConfigurationNeverCreatesTopicsAndIdentifiesTheService() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.kafka.admin.auto-create")).isEqualTo(false);
        assertThat(base.getProperty("spring.kafka.client-id")).hasToString("svc-acc-accounts-ledger");
        assertThat(base.getProperty("spring.kafka.producer.compression-type")).hasToString("lz4");
        assertThat(base.getProperty("spring.kafka.producer.acks")).hasToString("all");
        assertThat(base.getProperty("spring.kafka.producer.properties.enable.idempotence")).isEqualTo(true);
        assertThat((Integer) base.getProperty("spring.kafka.producer.properties.max.in.flight.requests.per.connection"))
            .isLessThanOrEqualTo(5);
    }

    @Test
    void theApplicationIsNamedPerTheNamingStandard() throws Exception {
        PropertySource<?> base = load("application.yml");

        assertThat(base.getProperty("spring.application.name")).hasToString("app.acc.accounts-ledger");
        assertThat(base.getProperty("management.metrics.tags.service")).hasToString("svc-acc-accounts-ledger");
    }

    @Test
    void producerTimeoutsLetKafkaConstructTheProducer() throws Exception {
        PropertySource<?> base = load("application.yml");
        long delivery = number(base, "spring.kafka.producer.properties.delivery.timeout.ms");
        long request = number(base, "spring.kafka.producer.properties.request.timeout.ms");
        long linger = number(base, "spring.kafka.producer.properties.linger.ms");

        assertThat(delivery).isEqualTo(30_000L);
        assertThat(delivery).isGreaterThanOrEqualTo(linger + request);
        assertThat(Duration.parse(base.getProperty("accounts.outbox.relay.send-timeout").toString()).toMillis())
            .isGreaterThan(delivery);

        Map<String, Object> config = new HashMap<>();
        config.put("bootstrap.servers", "localhost:9092");
        config.put("key.serializer", StringSerializer.class);
        config.put("value.serializer", StringSerializer.class);
        config.put("client.id", "kafka-config-test");
        config.put("acks", "all");
        config.put("enable.idempotence", true);
        config.put("max.in.flight.requests.per.connection", 5);
        config.put("delivery.timeout.ms", (int) delivery);
        config.put("request.timeout.ms", (int) request);
        config.put("linger.ms", (int) linger);
        config.put("compression.type", "lz4");
        // Construction validates delivery >= linger + request; no broker is contacted.
        new KafkaProducer<String, String>(config).close(Duration.ZERO);
    }

    private static long number(PropertySource<?> source, String name) {
        return Long.parseLong(source.getProperty(name).toString());
    }

    private static PropertySource<?> load(String file) throws Exception {
        return new YamlPropertySourceLoader().load(file, new ClassPathResource(file)).get(0);
    }
}
