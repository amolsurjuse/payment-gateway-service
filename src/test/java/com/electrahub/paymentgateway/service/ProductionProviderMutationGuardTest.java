package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductionProviderMutationGuardTest {

    @Test
    void rejectsRealProductionProviderWhenDisabled() {
        ProductionProviderMutationGuard guard = guard(false);

        assertThatThrownBy(() -> guard.requireAllowed(connection(GatewayProvider.STRIPE, GatewayEnvironment.PRODUCTION)))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo(ProductionProviderMutationGuard.ERROR_CODE)
                );
    }

    @Test
    void allowsRealSandboxProviderWhenProductionIsDisabled() {
        assertThatCode(() -> guard(false).requireAllowed(
                connection(GatewayProvider.STRIPE, GatewayEnvironment.SANDBOX)
        )).doesNotThrowAnyException();
    }

    @Test
    void allowsRealProductionProviderWhenEnabled() {
        assertThatCode(() -> guard(true).requireAllowed(
                connection(GatewayProvider.STRIPE, GatewayEnvironment.PRODUCTION)
        )).doesNotThrowAnyException();
    }

    @Test
    void leavesMockProductionRejectionToExistingMockPolicy() {
        assertThatCode(() -> guard(false).requireAllowed(
                connection(GatewayProvider.MOCK, GatewayEnvironment.PRODUCTION)
        )).doesNotThrowAnyException();
    }

    private ProductionProviderMutationGuard guard(boolean productionEnabled) {
        return new ProductionProviderMutationGuard(
                new GatewayProperties(Duration.ofMinutes(10), true, productionEnabled)
        );
    }

    private GatewayConnection connection(GatewayProvider provider, GatewayEnvironment environment) {
        return new GatewayConnection(
                UUID.randomUUID(), provider, environment, GatewayConnectionStatus.READY,
                "adapter-v1", "provider-profile", Set.of(), true, true, false,
                Instant.now(), Instant.now(), null, 1, Instant.now(), Instant.now()
        );
    }
}
