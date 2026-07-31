package com.electrahub.paymentgateway.service.stripe;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "STRIPE_SANDBOX_INTEGRATION_KEY", matches = ".+")
class StripeLiveSandboxTest {

    @Test
    void authorizesAndVoidsThroughTheRealAnonymousStripeSandbox() {
        String key = System.getenv("STRIPE_SANDBOX_INTEGRATION_KEY");
        ProviderCredentialResolver credentials = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return key;
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        StripePaymentGatewayAdapter adapter = new StripePaymentGatewayAdapter(
                new ProviderHttpTransport(), credentials, new ObjectMapper(), "https://api.stripe.com", 300
        );
        GatewayConnection connection = connection();

        assertThat(adapter.validate(connection).valid()).isTrue();
        String suffix = UUID.randomUUID().toString();
        GatewayOperationResultHolder authorization = authorize(adapter, connection, suffix);
        assertThat(authorization.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);

        GatewayOperationRequest voidRequest = request(
                GatewayOperationType.VOID,
                null,
                authorization.providerReference(),
                "void-" + suffix,
                "void-idem-" + suffix
        );
        assertThat(adapter.execute(voidRequest, connection).status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
    }

    private GatewayOperationResultHolder authorize(
            StripePaymentGatewayAdapter adapter,
            GatewayConnection connection,
            String suffix
    ) {
        var result = adapter.execute(request(
                GatewayOperationType.AUTHORIZE,
                "pm_card_visa",
                null,
                "authorize-" + suffix,
                "authorize-idem-" + suffix
        ), connection);
        return new GatewayOperationResultHolder(result.status(), result.providerReference());
    }

    private GatewayOperationRequest request(
            GatewayOperationType type,
            String paymentMethod,
            String providerReference,
            String operationId,
            String idempotencyKey
    ) {
        return new GatewayOperationRequest(
                UUID.randomUUID(), "integration-payment-intent", null, operationId, idempotencyKey, type,
                new BigDecimal("1.00"), "USD", "integration-account", paymentMethod, providerReference,
                "https://driver-portal.electrahub.net/payments/return", Instant.now()
        );
    }

    private GatewayConnection connection() {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.STRIPE, GatewayEnvironment.SANDBOX, GatewayConnectionStatus.READY,
                "stripe-rest-v1", "stripe-sandbox", Set.of(), true, false, false,
                null, null, null, 1, Instant.now(), Instant.now()
        );
    }

    private record GatewayOperationResultHolder(GatewayOperationStatus status, String providerReference) {
    }
}
