package com.electrahub.paymentgateway.service.twoc2p;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoC2PPaymentGatewayAdapterTest {

    private static final String DEMO_KEY = "test-demo-key-abcdefghijklmnopqrstuvwxyz-0123456789";

    private HttpServer server;
    private TwoC2PPaymentGatewayAdapter adapter;
    private ObjectMapper objectMapper;
    private JwtHs256Codec jwtCodec;
    private JsonNode lastPaymentTokenPayload;
    private JsonNode lastInquiryPayload;
    private int paymentTokenRequests;
    private int inquiryRequests;
    private String responseSigningKey;

    @BeforeEach
    void setUp() throws IOException {
        objectMapper = new ObjectMapper();
        jwtCodec = new JwtHs256Codec(objectMapper);
        responseSigningKey = DEMO_KEY;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payment/4.3/paymentToken", this::paymentToken);
        server.createContext("/payment/4.3/paymentInquiry", this::paymentInquiry);
        server.start();
        ProviderCredentialResolver credentials = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                throw new AssertionError("Public demo must not request a private credential");
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        adapter = new TwoC2PPaymentGatewayAdapter(
                new ProviderHttpTransport(),
                credentials,
                objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "JT01",
                DEMO_KEY,
                "https://api.electrahub.net/payment-gateway/api/v1/gateway/webhooks/test"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsHostedSingaporePaymentWithoutPrivateMerchantOnboarding() {
        assertThat(adapter.requiresCredential(connection())).isFalse();
        assertThat(adapter.validate(connection()).valid()).isTrue();

        var result = adapter.execute(request(GatewayOperationType.AUTHORIZE), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isEqualTo("token-demo");
        assertThat(result.action().url()).contains("sandbox-pgw-ui.2c2p.com");
        assertThat(result.action().data()).containsEntry("provider", "TWO_C2P");
    }

    @Test
    void validatesCredentialsWithASignedReadOnlyInquiry() {
        var validation = adapter.validate(connection());

        assertThat(validation.valid()).isTrue();
        assertThat(inquiryRequests).isEqualTo(1);
        assertThat(paymentTokenRequests).isZero();
        assertThat(lastInquiryPayload.path("invoiceNo").asText()).startsWith("EHVALIDATE");
    }

    @Test
    void rejectsAProbeThatIsNotSignedByTheConfiguredSandboxKey() {
        responseSigningKey = "different-signing-key-abcdefghijklmnopqrstuvwxyz";

        var validation = adapter.validate(connection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_CREDENTIAL_REJECTED");
    }

    @Test
    void privateSandboxRestrictsHostedPaymentToCardPreauthorization() {
        ProviderCredentialResolver privateCredentials = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return "{\"merchantId\":\"PRIVATE01\",\"secretKey\":\"" + DEMO_KEY + "\"}";
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        TwoC2PPaymentGatewayAdapter privateAdapter = new TwoC2PPaymentGatewayAdapter(
                new ProviderHttpTransport(), privateCredentials, objectMapper,
                "http://127.0.0.1:" + server.getAddress().getPort(), "JT01", DEMO_KEY,
                "https://api.electrahub.net/payment-gateway/api/v1/gateway/webhooks/test"
        );

        assertThat(privateAdapter.validate(privateConnection()).valid()).isTrue();
        var result = privateAdapter.execute(
                request(GatewayOperationType.AUTHORIZE, "electrahub://payment/return"),
                privateConnection()
        );

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(lastPaymentTokenPayload.path("paymentChannel").get(0).asText()).isEqualTo("CC");
        assertThat(lastPaymentTokenPayload.path("transactionMode").asText()).isEqualTo("PREAUTH");
        assertThat(lastPaymentTokenPayload.path("schemeReturnUrl").asText()).isEqualTo("electrahub://payment/return");
        assertThat(lastPaymentTokenPayload.path("appBundleID").asText()).isEqualTo("net.electrahub.driverportalios");
    }

    @Test
    void performsSignedPaymentInquiryAndRejectsUnavailableMaintenanceOperations() {
        var result = adapter.queryStatus(
                new GatewayOperationStatusQuery(UUID.randomUUID(), "operation-123", "idem", "token-demo"),
                connection()
        );

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(result.publicTransactionReference()).isEqualTo("reference-demo");
        assertThatThrownBy(() -> adapter.execute(request(GatewayOperationType.CAPTURE), connection()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("exchange keys");
    }

    @Test
    void verifiesAndNormalizesSignedBackendPaymentResponse() throws Exception {
        String token = new JwtHs256Codec(objectMapper).encode(Map.of(
                "merchantID", "JT01",
                "invoiceNo", "operation123",
                "tranRef", "transaction-321",
                "referenceNo", "reference-123",
                "respCode", "0000",
                "transactionDateTime", "20260731143000"
        ), DEMO_KEY);
        String body = objectMapper.writeValueAsString(Map.of("payload", token));

        var event = adapter.parseWebhook(connection(), body, Map.of());

        assertThat(event.merchantReference()).isEqualTo("operation123");
        assertThat(event.providerReference()).isEqualTo("transaction-321");
        assertThat(event.publicTransactionReference()).isEqualTo("reference-123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.CAPTURED);
    }

    @Test
    void rejectsUnsignedBackendPaymentResponse() {
        assertThatThrownBy(() -> adapter.parseWebhook(
                connection(),
                "{\"payload\":\"not-a-signed-token\"}",
                Map.of()
        )).isInstanceOf(GatewayWebhookVerificationException.class);
    }

    private void paymentToken(HttpExchange exchange) throws IOException {
        lastPaymentTokenPayload = requestPayload(exchange);
        paymentTokenRequests++;
        respondSigned(exchange, Map.of(
                "paymentToken", "token-demo",
                "webPaymentUrl", "https://sandbox-pgw-ui.2c2p.com/pay/demo",
                "respCode", "0000",
                "respDesc", "Success"
        ));
    }

    private void paymentInquiry(HttpExchange exchange) throws IOException {
        lastInquiryPayload = requestPayload(exchange);
        inquiryRequests++;
        String invoiceNo = lastInquiryPayload.path("invoiceNo").asText("operation123");
        String merchantId = lastInquiryPayload.path("merchantID").asText("JT01");
        respondSigned(exchange, Map.of(
                "merchantID", merchantId,
                "invoiceNo", invoiceNo,
                "referenceNo", "reference-demo",
                "respCode", invoiceNo.startsWith("EHVALIDATE") ? "2001" : "0000",
                "respDesc", invoiceNo.startsWith("EHVALIDATE") ? "Payment not found" : "Success"
        ));
    }

    private JsonNode requestPayload(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode outer = objectMapper.readTree(request);
        return jwtCodec.decodeAndVerify(outer.path("payload").asText(), DEMO_KEY);
    }

    private void respondSigned(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        String body = objectMapper.writeValueAsString(Map.of("payload", jwtCodec.encode(payload, responseSigningKey)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GatewayConnection connection() {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.TWO_C2P, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "2c2p-v4.3", TwoC2PPaymentGatewayAdapter.DEMO_PROFILE,
                Set.of(), false, false, false, null, null, null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayConnection privateConnection() {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.TWO_C2P, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "2c2p-v4.3", "2c2p-sandbox",
                Set.of(), true, false, false, null, null, null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayOperationRequest request(GatewayOperationType type) {
        return request(type, "https://driver.electrahub.net/payments/return");
    }

    private GatewayOperationRequest request(GatewayOperationType type, String returnUrl) {
        return new GatewayOperationRequest(
                UUID.randomUUID(), "payment-intent-123", null, "operation-123", "idem-123", type,
                new BigDecimal("10.00"), "SGD", "account-123", null, null, "token-demo",
                returnUrl, Instant.now()
        );
    }
}
