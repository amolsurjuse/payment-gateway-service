package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayRoutePolicyTest {

    private final GatewayRoutePolicy policy = policy(false);

    @Test
    void approvesActiveRouteWithAllRequiredCapabilities() {
        RouteResolution result = policy.evaluate(
                route(true, Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.MANUAL_CAPTURE)),
                connection(GatewayConnectionStatus.ACTIVE, Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.MANUAL_CAPTURE)),
                request(Set.of(GatewayCapability.AUTHORIZE)),
                Instant.parse("2026-07-21T12:00:00Z")
        );

        assertThat(result.approved()).isTrue();
        assertThat(result.code()).isEqualTo("APPROVED");
    }

    @Test
    void rejectsRouteWhenRequiredCapabilityIsAbsent() {
        RouteResolution result = policy.evaluate(
                route(true, Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.MANUAL_CAPTURE)),
                connection(GatewayConnectionStatus.ACTIVE, Set.of(GatewayCapability.AUTHORIZE)),
                request(Set.of(GatewayCapability.AUTHORIZE)),
                Instant.parse("2026-07-21T12:00:00Z")
        );

        assertThat(result.approved()).isFalse();
        assertThat(result.code()).isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED");
    }

    @Test
    void rejectsMockConnectionInProduction() {
        GatewayConnection connection = new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.MOCK, GatewayEnvironment.PRODUCTION,
                GatewayConnectionStatus.ACTIVE, "mock-v1", "sandbox", Set.of(GatewayCapability.AUTHORIZE),
                false, false, false, null, null, null, 1, Instant.now(), Instant.now()
        );

        RouteResolution result = policy.evaluate(
                route(true, Set.of(GatewayCapability.AUTHORIZE)),
                connection,
                request(Set.of(GatewayCapability.AUTHORIZE)),
                Instant.now()
        );

        assertThat(result.approved()).isFalse();
        assertThat(result.code()).isEqualTo("PAYMENT_ROUTE_DISABLED");
    }

    @Test
    void rejectsRealProductionProviderExecutionWhenProductionIsDisabled() {
        RouteResolution result = policy.evaluateOperation(
                route(true, Set.of(GatewayCapability.AUTHORIZE)),
                connection(GatewayProvider.STRIPE, GatewayEnvironment.PRODUCTION),
                GatewayOperationType.AUTHORIZE,
                Instant.now()
        );

        assertThat(result.approved()).isFalse();
        assertThat(result.code()).isEqualTo(ProductionProviderMutationGuard.ERROR_CODE);
    }

    @Test
    void allowsRealProductionProviderExecutionWhenProductionIsEnabled() {
        RouteResolution result = policy(true).evaluateOperation(
                route(true, Set.of(GatewayCapability.AUTHORIZE)),
                connection(GatewayProvider.STRIPE, GatewayEnvironment.PRODUCTION),
                GatewayOperationType.AUTHORIZE,
                Instant.now()
        );

        assertThat(result.approved()).isTrue();
    }

    @Test
    void keepsSettlementOperationsAvailableAfterRouteAndConnectionAreDisabled() {
        RouteResolution result = policy.evaluateOperation(
                route(false, Set.of(GatewayCapability.AUTHORIZE)),
                connection(GatewayConnectionStatus.DISABLED, Set.of(
                        GatewayCapability.AUTHORIZE,
                        GatewayCapability.CAPTURE,
                        GatewayCapability.VOID,
                        GatewayCapability.REFUND,
                        GatewayCapability.STATUS_QUERY
                )),
                GatewayOperationType.CAPTURE,
                Instant.parse("2026-07-21T12:00:00Z")
        );

        assertThat(result.approved()).isTrue();
        assertThat(result.code()).isEqualTo("APPROVED");
    }

    @Test
    void stillRejectsNewAuthorizationsAfterRouteIsDisabled() {
        RouteResolution result = policy.evaluateOperation(
                route(false, Set.of(GatewayCapability.AUTHORIZE)),
                connection(GatewayConnectionStatus.DISABLED, Set.of(GatewayCapability.AUTHORIZE)),
                GatewayOperationType.AUTHORIZE,
                Instant.parse("2026-07-21T12:00:00Z")
        );

        assertThat(result.approved()).isFalse();
        assertThat(result.code()).isEqualTo("PAYMENT_ROUTE_DISABLED");
    }

    private PaymentRoute route(boolean enabled, Set<GatewayCapability> requiredCapabilities) {
        return new PaymentRoute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "US", "USD", "USD",
                PaymentChannel.MOBILE, PaymentMethodType.CARD_ON_FILE, 0, enabled, requiredCapabilities,
                null, null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayConnection connection(GatewayConnectionStatus status, Set<GatewayCapability> capabilities) {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.MOCK, GatewayEnvironment.SANDBOX, status,
                "mock-v1", "sandbox", capabilities, false, false, false,
                Instant.now(), Instant.now(), null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayConnection connection(GatewayProvider provider, GatewayEnvironment environment) {
        return new GatewayConnection(
                UUID.randomUUID(), provider, environment, GatewayConnectionStatus.ACTIVE,
                "adapter-v1", "provider-profile", Set.of(GatewayCapability.AUTHORIZE), true, true, false,
                Instant.now(), Instant.now(), null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayRoutePolicy policy(boolean productionEnabled) {
        GatewayProperties properties = new GatewayProperties(Duration.ofMinutes(10), true, productionEnabled);
        return new GatewayRoutePolicy(new ProductionProviderMutationGuard(properties));
    }

    private RouteResolutionRequest request(Set<GatewayCapability> requiredCapabilities) {
        return new RouteResolutionRequest(
                UUID.randomUUID(), "US", "USD", "USD", PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE, requiredCapabilities
        );
    }
}
