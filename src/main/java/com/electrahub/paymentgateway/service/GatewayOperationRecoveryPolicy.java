package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Owns bounded retry timing for uncertain provider outcomes. It intentionally
 * has no provider or database dependency so retry timing remains deterministic.
 */
@Component
public class GatewayOperationRecoveryPolicy {

    private final GatewayProperties properties;

    public GatewayOperationRecoveryPolicy(GatewayProperties properties) {
        this.properties = properties;
    }

    public Instant leaseUntil(Instant now) {
        return now.plus(properties.operationRecoveryLease());
    }

    public Instant nextAttemptAt(Instant now, int recoveryAttemptCount) {
        int attempt = Math.max(1, recoveryAttemptCount);
        long multiplier = 1L;
        for (int index = 1; index < attempt && multiplier < 1_000_000L; index++) {
            multiplier = Math.min(1_000_000L, multiplier * 2L);
        }

        Duration delay;
        try {
            delay = properties.operationRecoveryInitialBackoff().multipliedBy(multiplier);
        } catch (ArithmeticException ignored) {
            delay = properties.operationRecoveryMaxBackoff();
        }
        if (delay.compareTo(properties.operationRecoveryMaxBackoff()) > 0) {
            delay = properties.operationRecoveryMaxBackoff();
        }
        return now.plus(delay);
    }
}
