package com.electrahub.paymentgateway.service.adyen;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AdyenPaymentGatewayAdapterTest {

    private static final String HMAC_KEY = "44782DEF547AAA06C910C43932B1EB0C71FC68D9D0C057550C48EC2ACF6BA056";

    private HttpServer server;
    private AdyenPaymentGatewayAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v72/paymentMethods", exchange -> respond(exchange, "{\"paymentMethods\":[]}"));
        server.createContext("/v72/sessions", exchange -> respond(exchange,
                "{\"id\":\"CS1234567890\",\"sessionData\":\"encrypted-session-data\",\"expiresAt\":\"2026-08-01T14:00:00Z\"}"));
        server.start();
        ProviderCredentialResolver resolver = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return """
                        {"apiKey":"AQEtest","merchantAccount":"ElectraHubTest","clientKey":"test_client_key",
                         "countryCode":"SE","manualCaptureEnabled":true}
                        """;
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.of(HMAC_KEY);
            }
        };
        adapter = new AdyenPaymentGatewayAdapter(
                new ProviderHttpTransport(), resolver, new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v72"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validatesManualCaptureAccountAndCreatesSdkSession() {
        assertThat(adapter.validate(connection()).valid()).isTrue();

        var result = adapter.execute(request(), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isEqualTo("CS1234567890");
        assertThat(result.action().clientSecret()).isEqualTo("encrypted-session-data");
        assertThat(result.action().data()).containsEntry("clientKey", "test_client_key");
    }

    @Test
    void verifiesEveryNotificationInAdyenBatch() throws Exception {
        String payload = "7914073381342284::ElectraHubTest:operation-123:1130:SEK:AUTHORISATION:true";
        String signature = hmac(payload);
        String body = """
                {"notificationItems":[{"NotificationRequestItem":{
                  "additionalData":{"hmacSignature":"%s"},"amount":{"value":1130,"currency":"SEK"},
                  "pspReference":"7914073381342284","eventCode":"AUTHORISATION",
                  "eventDate":"2026-07-31T14:00:00Z","merchantAccountCode":"ElectraHubTest",
                  "merchantReference":"operation-123","success":"true"}}]}
                """.formatted(signature).trim();

        var events = adapter.parseWebhooks(connection(), body, Map.of());

        assertThat(events).hasSize(1);
        assertThat(events.getFirst().merchantReference()).isEqualTo("operation-123");
        assertThat(events.getFirst().outcome()).isEqualTo(GatewayWebhookOutcome.AUTHORIZED);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("X-API-Key")).isEqualTo("AQEtest");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private String hmac(String payload) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(HMAC_KEY), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    }

    private GatewayConnection connection() {
        return new GatewayConnection(UUID.randomUUID(), GatewayProvider.ADYEN, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "adyen-checkout-v72", "adyen-sandbox", Set.of(), true, true,
                false, null, null, null, 1, Instant.now(), Instant.now());
    }

    private GatewayOperationRequest request() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-123",
                "idem-123", GatewayOperationType.AUTHORIZE, new BigDecimal("25.00"), "SEK", "account-123", null, null,
                "https://driver.electrahub.net/payments/return", Instant.now());
    }
}
