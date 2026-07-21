package com.electrahub.paymentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.electrahub.paymentgateway.config.GatewayProperties;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class GatewayOperationRecoveryPolicyTest {

    private final GatewayOperationRecoveryPolicy policy = new GatewayOperationRecoveryPolicy(
            new GatewayProperties(
                    Duration.ofMinutes(10),
                    true,
                    false,
                    Duration.ofSeconds(45),
                    Duration.ofSeconds(30),
                    Duration.ofMinutes(5)
            )
    );

    @Test
    void createsABoundedRecoveryLease() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");

        assertThat(policy.leaseUntil(now)).isEqualTo(now.plusSeconds(45));
    }

    @Test
    void exponentiallyBacksOffAndCapsProviderInquiries() {
        Instant now = Instant.parse("2026-07-21T12:00:00Z");

        assertThat(policy.nextAttemptAt(now, 1)).isEqualTo(now.plusSeconds(30));
        assertThat(policy.nextAttemptAt(now, 2)).isEqualTo(now.plus(Duration.ofMinutes(1)));
        assertThat(policy.nextAttemptAt(now, 5)).isEqualTo(now.plus(Duration.ofMinutes(5)));
        assertThat(policy.nextAttemptAt(now, 50)).isEqualTo(now.plus(Duration.ofMinutes(5)));
    }
}
