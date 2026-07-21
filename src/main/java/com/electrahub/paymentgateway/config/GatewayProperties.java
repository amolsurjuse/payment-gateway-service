package com.electrahub.paymentgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.gateway")
public record GatewayProperties(
        Duration routeCacheTtl,
        boolean mockProviderEnabled,
        boolean productionEnabled,
        Duration operationRecoveryLease,
        Duration operationRecoveryInitialBackoff,
        Duration operationRecoveryMaxBackoff
) {

    public GatewayProperties(Duration routeCacheTtl, boolean mockProviderEnabled, boolean productionEnabled) {
        this(
                routeCacheTtl,
                mockProviderEnabled,
                productionEnabled,
                Duration.ofSeconds(45),
                Duration.ofSeconds(30),
                Duration.ofMinutes(30)
        );
    }

    public GatewayProperties {
        routeCacheTtl = routeCacheTtl == null || routeCacheTtl.isNegative() || routeCacheTtl.isZero()
                ? Duration.ofMinutes(10)
                : routeCacheTtl;
        operationRecoveryLease = positiveOrDefault(operationRecoveryLease, Duration.ofSeconds(45));
        operationRecoveryInitialBackoff = positiveOrDefault(operationRecoveryInitialBackoff, Duration.ofSeconds(30));
        operationRecoveryMaxBackoff = positiveOrDefault(operationRecoveryMaxBackoff, Duration.ofMinutes(30));
        if (operationRecoveryMaxBackoff.compareTo(operationRecoveryInitialBackoff) < 0) {
            operationRecoveryMaxBackoff = operationRecoveryInitialBackoff;
        }
    }

    private static Duration positiveOrDefault(Duration value, Duration fallback) {
        return value == null || value.isNegative() || value.isZero() ? fallback : value;
    }
}
