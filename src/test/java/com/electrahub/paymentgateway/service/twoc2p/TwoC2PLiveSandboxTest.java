package com.electrahub.paymentgateway.service.twoc2p;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayActionType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TWO_C2P_DEMO_INTEGRATION_KEY", matches = ".+")
class TwoC2PLiveSandboxTest {

    @Test
    void createsAHostedPaymentTokenThroughThePublishedSingaporeDemo() {
        String demoKey = System.getenv("TWO_C2P_DEMO_INTEGRATION_KEY");
        ProviderCredentialResolver noPrivateCredential = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                throw new AssertionError("The public demo must not resolve a private merchant credential.");
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        ProviderHttpTransport transport = new ProviderHttpTransport();
        ObjectMapper objectMapper = new ObjectMapper();
        TwoC2PPaymentGatewayAdapter adapter = new TwoC2PPaymentGatewayAdapter(
                transport,
                noPrivateCredential,
                objectMapper,
                "https://sandbox-pgw.2c2p.com",
                "JT01",
                demoKey,
                ""
        );
        GatewayConnection connection = connection();

        assertThat(adapter.validate(connection).valid()).isTrue();
        String suffix = UUID.randomUUID().toString();
        var result = execute(adapter, connection, suffix, transport, objectMapper, demoKey);

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isNotBlank();
        assertThat(result.action()).isNotNull();
        assertThat(result.action().type()).isEqualTo(GatewayActionType.REDIRECT);
        assertThat(result.action().url()).startsWith("https://");
    }

    private com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult execute(
            TwoC2PPaymentGatewayAdapter adapter,
            GatewayConnection connection,
            String suffix,
            ProviderHttpTransport transport,
            ObjectMapper objectMapper,
            String demoKey
    ) {
        try {
            return adapter.execute(new GatewayOperationRequest(
                    UUID.randomUUID(),
                    "integration-payment-intent",
                    null,
                    "authorize-" + suffix,
                    "authorize-idem-" + suffix,
                    GatewayOperationType.AUTHORIZE,
                    new BigDecimal("1.00"),
                    "SGD",
                    "integration-account",
                    null,
                    null,
                    null,
                    "https://driver-portal.electrahub.net/payments/return",
                    Instant.now()
            ), connection);
        } catch (GatewayBusinessException exception) {
            String publishedExampleCode = publishedExampleResponseCode(transport, objectMapper, demoKey);
            throw new AssertionError(
                    "2C2P adapter failed with " + exception.code()
                            + "; the provider's minimal published request returned TWO_C2P_" + publishedExampleCode,
                    exception
            );
        }
    }

    private String publishedExampleResponseCode(
            ProviderHttpTransport transport,
            ObjectMapper objectMapper,
            String demoKey
    ) {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("merchantID", "JT01");
            payload.put("invoiceNo", Long.toString(System.currentTimeMillis()).substring(3));
            payload.put("description", "item 1");
            payload.put("amount", new BigDecimal("1.00"));
            payload.put("currencyCode", "SGD");
            JwtHs256Codec codec = new JwtHs256Codec(objectMapper);
            String body = objectMapper.writeValueAsString(Map.of("payload", codec.encode(payload, demoKey)));
            var response = transport.postJson(
                    URI.create("https://sandbox-pgw.2c2p.com/payment/4.3/paymentToken"),
                    Map.of("Accept", "application/json"),
                    body
            );
            var outer = objectMapper.readTree(response.body());
            String signedPayload = outer.path("payload").asText();
            return (signedPayload.isBlank() ? outer : codec.decodeAndVerify(signedPayload, demoKey))
                    .path("respCode").asText("UNKNOWN");
        } catch (Exception exception) {
            return "PROBE_FAILED";
        }
    }

    private GatewayConnection connection() {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.TWO_C2P, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "2c2p-v4.3", "2c2p-sandbox-sg-demo",
                Set.of(), false, false, false, null, null, null, 1, Instant.now(), Instant.now()
        );
    }
}
