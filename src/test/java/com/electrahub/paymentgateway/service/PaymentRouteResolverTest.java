package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PaymentRouteResolverTest {

    @Test
    void resolvesFromCanonicalNetworkScopeAndCachesApprovedRoute() {
        GatewayConfigurationService configurationService = mock(GatewayConfigurationService.class);
        GatewayRouteCandidate candidate = new GatewayRouteCandidate(activeRoute(), activeConnection());
        when(configurationService.findCandidates(any(ScopedRouteResolutionRequest.class))).thenReturn(List.of(candidate));

        PaymentRouteResolver resolver = new PaymentRouteResolver(
                configurationService,
                new GatewayRoutePolicy(new ProductionProviderMutationGuard(
                        new GatewayProperties(Duration.ofMinutes(10), true, false)
                )),
                new GatewayRouteCache(new GatewayProperties(Duration.ofMinutes(10), true, false))
        );
        ScopedRouteResolutionRequest request = new ScopedRouteResolutionRequest(
                "ENT-NL-DEMO",
                "NET-NL-DEMO",
                "NL",
                "EUR",
                PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE,
                Set.of(GatewayCapability.AUTHORIZE)
        );

        RouteResolution first = resolver.resolve(request);
        RouteResolution second = resolver.resolve(request);

        assertThat(first.approved()).isTrue();
        assertThat(first.routeId()).isEqualTo(candidate.route().id());
        assertThat(first.merchantAccountId()).isEqualTo(candidate.route().merchantAccountId());
        assertThat(first.connectionId()).isEqualTo(candidate.connection().id());
        assertThat(first.settlementCurrency()).isEqualTo("EUR");
        assertThat(first.routeConfigurationVersion()).isEqualTo(candidate.route().configurationVersion());
        assertThat(first.connectionConfigurationVersion()).isEqualTo(candidate.connection().configurationVersion());
        assertThat(second).isEqualTo(first);
        verify(configurationService, times(1)).findCandidates(request);
    }

    private PaymentRoute activeRoute() {
        return new PaymentRoute(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "NL",
                "EUR",
                "EUR",
                PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE,
                10,
                true,
                Set.of(GatewayCapability.AUTHORIZE),
                null,
                null,
                1,
                Instant.now(),
                Instant.now()
        );
    }

    private GatewayConnection activeConnection() {
        return new GatewayConnection(
                UUID.randomUUID(),
                GatewayProvider.MOCK,
                GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.ACTIVE,
                "mock-v1",
                "sandbox-default",
                Set.of(GatewayCapability.AUTHORIZE),
                true,
                false,
                false,
                Instant.now(),
                Instant.now(),
                null,
                1,
                Instant.now(),
                Instant.now()
        );
    }
}
