package com.electrahub.paymentgateway.service.stripe;

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
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
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
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StripePaymentGatewayAdapterTest {

    private HttpServer server;
    private String baseUrl;
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private StripePaymentGatewayAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payment_intents", this::paymentIntent);
        server.createContext("/v1/refunds", exchange -> respond(exchange, 200, "{\"id\":\"re_test\",\"status\":\"succeeded\"}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        ProviderCredentialResolver credentials = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return "sk_test_electrahub";
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.of("whsec_test");
            }
        };
        adapter = new StripePaymentGatewayAdapter(
                new ProviderHttpTransport(), credentials, new ObjectMapper(), baseUrl, 300
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validatesSandboxAndAuthorizesWithManualCaptureAndIdempotency() {
        assertThat(adapter.validate(connection()).valid()).isTrue();

        var result = adapter.execute(request(GatewayOperationType.AUTHORIZE, "pm_card_visa", null), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(result.providerReference()).isEqualTo("pi_authorized");
        assertThat(lastBody.get())
                .contains("amount=2510")
                .contains("currency=usd")
                .contains("capture_method=manual")
                .contains("payment_method=pm_card_visa")
                .contains("customer=cus_test");
    }

    @Test
    void returnsStructuredCustomerActionForThreeDs() {
        var result = adapter.execute(request(GatewayOperationType.AUTHORIZE, "pm_action", null), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.action()).isNotNull();
        assertThat(result.action().clientSecret()).isEqualTo("pi_action_secret_test");
        assertThat(result.action().url()).isEqualTo("https://stripe.test/3ds");
    }

    @Test
    void capturesVoidsRefundsAndQueriesTheOriginalPaymentIntent() {
        assertThat(adapter.execute(request(GatewayOperationType.CAPTURE, null, "pi_original"), connection()).status())
                .isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(adapter.execute(request(GatewayOperationType.VOID, null, "pi_original"), connection()).status())
                .isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(adapter.execute(request(GatewayOperationType.REFUND, null, "pi_original"), connection()).status())
                .isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(adapter.queryStatus(
                new GatewayOperationStatusQuery(UUID.randomUUID(), "op", "idem", "pi_original"), connection()
        ).status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
    }

    @Test
    void verifiesAndNormalizesStripeWebhookFromTheRawBody() throws Exception {
        long timestamp = Instant.now().getEpochSecond();
        String body = """
                {"id":"evt_authorized","type":"payment_intent.amount_capturable_updated","created":1710000000,
                 "data":{"object":{"object":"payment_intent","id":"pi_authorized",
                 "metadata":{"electrahub_payment_intent_id":"payment-intent-123"}}}}
                """.trim();
        String signature = hmac(timestamp + "." + body, "whsec_test");

        var event = adapter.parseWebhook(connection(), body, Map.of(
                "stripe-signature", "t=" + timestamp + ",v1=" + signature
        ));

        assertThat(event.providerEventId()).isEqualTo("evt_authorized");
        assertThat(event.providerReference()).isEqualTo("pi_authorized");
        assertThat(event.merchantReference()).isEqualTo("payment-intent-123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.AUTHORIZED);
    }

    @Test
    void rejectsStripeWebhookWithInvalidSignature() {
        long timestamp = Instant.now().getEpochSecond();

        assertThatThrownBy(() -> adapter.parseWebhook(
                connection(),
                "{\"id\":\"evt_invalid\"}",
                Map.of("stripe-signature", "t=" + timestamp + ",v1=invalid")
        )).isInstanceOf(GatewayWebhookVerificationException.class);
    }

    private String hmac(String value, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private void paymentIntent(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody.set(body);
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer sk_test_electrahub");

        if (exchange.getRequestMethod().equals("GET") && exchange.getRequestURI().getQuery() != null) {
            respond(exchange, 200, "{\"object\":\"list\",\"data\":[]}");
        } else if (path.endsWith("/capture")) {
            respond(exchange, 200, "{\"id\":\"pi_original\",\"status\":\"succeeded\"}");
        } else if (path.endsWith("/cancel")) {
            respond(exchange, 200, "{\"id\":\"pi_original\",\"status\":\"canceled\"}");
        } else if (exchange.getRequestMethod().equals("GET")) {
            respond(exchange, 200, "{\"id\":\"pi_original\",\"status\":\"requires_capture\"}");
        } else if (body.contains("payment_method=pm_action")) {
            respond(exchange, 200, """
                    {"id":"pi_action","status":"requires_action","client_secret":"pi_action_secret_test",
                     "next_action":{"redirect_to_url":{"url":"https://stripe.test/3ds"}}}
                    """);
        } else {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-123");
            respond(exchange, 200, "{\"id\":\"pi_authorized\",\"status\":\"requires_capture\"}");
        }
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GatewayConnection connection() {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.STRIPE, GatewayEnvironment.SANDBOX, GatewayConnectionStatus.READY,
                "stripe-rest-v1", "stripe-sandbox", Set.of(), true, true, false,
                null, null, null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayOperationRequest request(GatewayOperationType type, String paymentMethod, String providerReference) {
        return new GatewayOperationRequest(
                UUID.randomUUID(), "payment-intent-123", null, "operation-123", "idem-123", type,
                new BigDecimal("25.10"), "USD", "account-123", paymentMethod, "cus_test", providerReference,
                "https://driver.electrahub.net/payments/return", Instant.now()
        );
    }
}
