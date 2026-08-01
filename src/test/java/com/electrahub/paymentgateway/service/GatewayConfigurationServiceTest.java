package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreatePaymentRouteRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayConfigurationServiceTest {

    @Test
    void requiresASeparateAuditedActionToEnableANewRoute() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GatewayConfigurationService service = new GatewayConfigurationService(
                jdbcTemplate,
                mock(PaymentGatewayRegistry.class),
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        );
        CreatePaymentRouteRequest request = new CreatePaymentRouteRequest(
                UUID.randomUUID(),
                "US",
                "USD",
                "USD",
                PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE,
                100,
                true,
                Set.of(),
                null,
                null
        );

        assertThatThrownBy(() -> service.createPaymentRoute(request, UUID.randomUUID()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("Create payment routes disabled");
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void rejectsProductionValidationBeforeCallingTheProvider() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        GatewayConfigurationService service = spy(new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        ));
        GatewayConnection connection = connection(GatewayConnectionStatus.DRAFT);
        doReturn(connection).when(service).requireConnection(connection.id());

        assertThatThrownBy(() -> service.validateConnection(connection.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(ProductionProviderMutationGuard.ERROR_CODE)
                );

        verifyNoInteractions(jdbcTemplate, registry);
    }

    @Test
    void rejectsProductionActivationEvenWhenConnectionIsReady() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        GatewayConfigurationService service = spy(new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        ));
        GatewayConnection connection = connection(GatewayConnectionStatus.READY);
        doReturn(connection).when(service).requireConnection(connection.id());

        assertThatThrownBy(() -> service.activateConnection(connection.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo(ProductionProviderMutationGuard.ERROR_CODE)
                );

        verifyNoInteractions(jdbcTemplate, registry);
    }

    @Test
    void acceptsHostedCheckoutOnlyForHostedCheckoutProviders() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                GatewayConfigurationService.requirePaymentMethodSupported(
                        GatewayProvider.MOLLIE,
                        PaymentMethodType.HOSTED_CHECKOUT
                )
        ).doesNotThrowAnyException();

        assertThatThrownBy(() -> GatewayConfigurationService.requirePaymentMethodSupported(
                GatewayProvider.MOLLIE,
                PaymentMethodType.CARD_ON_FILE
        )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.code())
                        .isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED")
        );
    }

    @Test
    void rejectsHostedCheckoutForCardOnFileProviders() {
        assertThatThrownBy(() -> GatewayConfigurationService.requirePaymentMethodSupported(
                GatewayProvider.STRIPE,
                PaymentMethodType.HOSTED_CHECKOUT
        )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.code())
                        .isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED")
        );
    }

    @Test
    void requiresManualCaptureCapabilitiesForHostedCheckout() {
        assertThatThrownBy(() -> GatewayConfigurationService.requirePaymentMethodCapabilities(
                PaymentMethodType.HOSTED_CHECKOUT,
                Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.CAPTURE)
        )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                org.assertj.core.api.Assertions.assertThat(exception.code())
                        .isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED")
        );

        org.assertj.core.api.Assertions.assertThatCode(() ->
                GatewayConfigurationService.requirePaymentMethodCapabilities(
                        PaymentMethodType.HOSTED_CHECKOUT,
                        Set.of(
                                GatewayCapability.AUTHORIZE,
                                GatewayCapability.MANUAL_CAPTURE,
                                GatewayCapability.CAPTURE
                        )
                )
        ).doesNotThrowAnyException();
    }

    private GatewayProperties properties(boolean productionEnabled) {
        return new GatewayProperties(Duration.ofMinutes(10), true, productionEnabled);
    }

    private GatewayConnection connection(GatewayConnectionStatus status) {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.STRIPE, GatewayEnvironment.PRODUCTION, status,
                "stripe-rest-v1", "stripe-production", Set.of(), true, true, false,
                Instant.now(), Instant.now(), null, 1, Instant.now(), Instant.now()
        );
    }
}
