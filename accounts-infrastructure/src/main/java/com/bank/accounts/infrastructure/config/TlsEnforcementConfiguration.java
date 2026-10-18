package com.bank.accounts.infrastructure.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Runs {@link StartupTlsAssertion} as a bean factory post-processor: before any
 * other bean is created, so a pod with a non-TLS datasource URL or Kafka client
 * never opens a connection. The service finds it by component scan; the migration
 * Job context ({@code DatabaseMigration}, no component scan) imports it explicitly,
 * so the Job's datasource URL is asserted at startup as well as at render time.
 */
@Configuration(proxyBeanMethods = false)
public class TlsEnforcementConfiguration {

    /** Static so the post-processor exists before the configuration class itself is instantiated. */
    @Bean
    static BeanFactoryPostProcessor startupTlsAssertion(Environment environment) {
        return beanFactory -> StartupTlsAssertion.check(environment);
    }
}
