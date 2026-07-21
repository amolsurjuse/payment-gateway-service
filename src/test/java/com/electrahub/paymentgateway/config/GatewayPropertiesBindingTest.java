package com.electrahub.paymentgateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPropertiesBindingTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class)
            .withPropertyValues(
                    "app.gateway.route-cache-ttl=7m",
                    "app.gateway.mock-provider-enabled=false",
                    "app.gateway.production-enabled=true",
                    "app.gateway.operation-recovery-lease=60s",
                    "app.gateway.operation-recovery-initial-backoff=10s",
                    "app.gateway.operation-recovery-max-backoff=2m"
            );

    @Test
    void bindsTheCanonicalRecordConstructorWhenConvenienceConstructorAlsoExists() {
        contextRunner.run(context -> {
            GatewayProperties properties = context.getBean(GatewayProperties.class);

            assertThat(properties.routeCacheTtl()).isEqualTo(Duration.ofMinutes(7));
            assertThat(properties.mockProviderEnabled()).isFalse();
            assertThat(properties.productionEnabled()).isTrue();
            assertThat(properties.operationRecoveryLease()).isEqualTo(Duration.ofSeconds(60));
            assertThat(properties.operationRecoveryInitialBackoff()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.operationRecoveryMaxBackoff()).isEqualTo(Duration.ofMinutes(2));
        });
    }

    @Configuration(proxyBeanMethods = false)
    @ConfigurationPropertiesScan(basePackageClasses = GatewayProperties.class)
    static class PropertiesConfiguration {
    }
}
