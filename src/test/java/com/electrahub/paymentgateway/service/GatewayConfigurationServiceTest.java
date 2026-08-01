package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreatePaymentRouteRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateGatewayConnectionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

class GatewayConfigurationServiceTest {

    @Test
    void rejectsUnapprovedProviderCredentialReferencesBeforeWriting() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        GatewayConfigurationService service = new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        );
        CreateGatewayConnectionRequest request = new CreateGatewayConnectionRequest(
                GatewayProvider.MOLLIE,
                GatewayEnvironment.SANDBOX,
                "mollie-sandbox",
                "env:APP_SECURITY_INTERNAL_TOKEN",
                null,
                null,
                Set.of()
        );

        assertThatThrownBy(() -> service.createConnection(request, UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("GATEWAY_SECRET_REFERENCE_NOT_APPROVED")
                );

        verifyNoInteractions(jdbcTemplate, registry);
    }

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
    void rejectsAdyenActivationWithoutAWebhookSecretReference() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        GatewayConfigurationService service = spy(new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        ));
        GatewayConnection connection = new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.ADYEN, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "adyen-checkout-v72", "adyen-sandbox", Set.of(),
                true, false, false, Instant.now(), Instant.now(), null, 1, Instant.now(), Instant.now()
        );
        doReturn(connection).when(service).requireConnection(connection.id());

        assertThatThrownBy(() -> service.activateConnection(connection.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("ADYEN_WEBHOOK_SECRET_REQUIRED")
                );

        verifyNoInteractions(jdbcTemplate, registry);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void privateTwoC2PActivationRevalidatesAndRejectsAProtectedProbeFailure() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        PaymentGatewayAdapter adapter = mock(PaymentGatewayAdapter.class);
        GatewayConfigurationService service = spy(new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        ));
        Instant now = Instant.now();
        GatewayConnection connection = new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.TWO_C2P, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "2c2p-v4.3", "2c2p-sandbox", Set.of(),
                true, false, true, now, now, null, 1, now, now
        );
        doReturn(connection).when(service).requireConnection(connection.id());
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("credential_secret_reference"))
                    .thenReturn("env:APP_GATEWAY_2C2P_CREDENTIAL");
            when(resultSet.getString("webhook_secret_reference")).thenReturn(null);
            when(resultSet.getString("certificate_secret_reference"))
                    .thenReturn("env:APP_GATEWAY_2C2P_CERTIFICATE");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(registry.find(GatewayProvider.TWO_C2P)).thenReturn(Optional.of(adapter));
        when(adapter.requiresCredential(connection)).thenReturn(true);
        when(adapter.validate(connection)).thenReturn(new ConnectionValidation(
                false,
                "TWO_C2P_MAINTENANCE_PROBE_REJECTED",
                "2C2P rejected the protected sandbox maintenance probe.",
                Set.of(),
                "2c2p-v4.3"
        ));

        assertThatThrownBy(() -> service.activateConnection(connection.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("TWO_C2P_MAINTENANCE_PROBE_REJECTED")
                );

        verify(adapter).validate(connection);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void privateTwoC2PActivationRequiresManualCaptureCapabilitiesFromRevalidation() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        PaymentGatewayAdapter adapter = mock(PaymentGatewayAdapter.class);
        GatewayConfigurationService service = spy(new GatewayConfigurationService(
                jdbcTemplate,
                registry,
                new GatewayRouteCache(properties(false)),
                new ProductionProviderMutationGuard(properties(false))
        ));
        Instant now = Instant.now();
        GatewayConnection connection = new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.TWO_C2P, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "2c2p-v4.3", "2c2p-sandbox", Set.of(),
                true, false, true, now, now, null, 1, now, now
        );
        doReturn(connection).when(service).requireConnection(connection.id());
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getString("credential_secret_reference"))
                    .thenReturn("env:APP_GATEWAY_2C2P_CREDENTIAL");
            when(resultSet.getString("webhook_secret_reference")).thenReturn(null);
            when(resultSet.getString("certificate_secret_reference"))
                    .thenReturn("env:APP_GATEWAY_2C2P_CERTIFICATE");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(registry.find(GatewayProvider.TWO_C2P)).thenReturn(Optional.of(adapter));
        when(adapter.requiresCredential(connection)).thenReturn(true);
        when(adapter.validate(connection)).thenReturn(new ConnectionValidation(
                true,
                "READY",
                "Payment credentials validated without maintenance proof.",
                Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.STATUS_QUERY),
                "2c2p-v4.3"
        ));

        assertThatThrownBy(() -> service.activateConnection(connection.id(), UUID.randomUUID()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        org.assertj.core.api.Assertions.assertThat(exception.code())
                                .isEqualTo("TWO_C2P_MAINTENANCE_CAPABILITIES_REQUIRED")
                );

        verify(adapter).validate(connection);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void acceptsHostedCheckoutOnlyForCustomerInteractiveProviders() {
        for (GatewayProvider provider : Set.of(
                GatewayProvider.MOLLIE,
                GatewayProvider.ADYEN,
                GatewayProvider.RAZORPAY,
                GatewayProvider.TWO_C2P
        )) {
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    GatewayConfigurationService.requirePaymentMethodSupported(
                            provider,
                            PaymentMethodType.HOSTED_CHECKOUT
                    )
            ).doesNotThrowAnyException();

            assertThatThrownBy(() -> GatewayConfigurationService.requirePaymentMethodSupported(
                    provider,
                    PaymentMethodType.CARD_ON_FILE
            )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                    org.assertj.core.api.Assertions.assertThat(exception.code())
                            .isEqualTo("PAYMENT_METHOD_NOT_SUPPORTED")
            );
        }
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
    void keepsPublicTwoC2PDemoCapabilitiesRouteIneligible() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
                GatewayConfigurationService.requirePaymentMethodSupported(
                        GatewayProvider.TWO_C2P,
                        PaymentMethodType.HOSTED_CHECKOUT
                )
        ).doesNotThrowAnyException();

        assertThatThrownBy(() -> GatewayConfigurationService.requirePaymentMethodCapabilities(
                PaymentMethodType.HOSTED_CHECKOUT,
                Set.of(
                        GatewayCapability.AUTHORIZE,
                        GatewayCapability.STATUS_QUERY,
                        GatewayCapability.THREE_DS_SCA
                )
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
