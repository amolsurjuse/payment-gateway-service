package com.electrahub.paymentgateway.service.razorpay;

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
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RazorpayPaymentGatewayAdapterTest {

    private HttpServer server;
    private RazorpayPaymentGatewayAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", exchange -> respond(exchange, 200, "{\"entity\":\"collection\",\"items\":[]}"));
        server.createContext("/v1/orders", exchange -> respond(exchange, 200,
                "{\"id\":\"order_test123\",\"amount\":2500,\"currency\":\"INR\",\"status\":\"created\"}"));
        server.start();
        ProviderCredentialResolver resolver = resolver();
        adapter = new RazorpayPaymentGatewayAdapter(
                new ProviderHttpTransport(), resolver, new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort()
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void validatesAndCreatesSdkCheckoutOrderWithoutReceivingCardData() {
        assertThat(adapter.validate(connection()).valid()).isTrue();

        var result = adapter.execute(request(), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isEqualTo("order_test123");
        assertThat(result.action().data())
                .containsEntry("keyId", "rzp_test_electrahub")
                .containsEntry("orderId", "order_test123");
    }

    @Test
    void verifiesPaymentAuthorizedWebhook() throws Exception {
        String body = """
                {"event":"payment.authorized","payload":{"payment":{"entity":{
                  "id":"pay_test123","order_id":"order_test123","created_at":1785500000}}}}
                """.trim();

        var event = adapter.parseWebhook(connection(), body, Map.of(
                "x-razorpay-signature", hmac(body, "webhook-secret"),
                "x-razorpay-event-id", "event-test-123"
        ));

        assertThat(event.providerReference()).isEqualTo("order_test123");
        assertThat(event.publicTransactionReference()).isEqualTo("pay_test123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.AUTHORIZED);
    }

    private ProviderCredentialResolver resolver() {
        return new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return "{\"keyId\":\"rzp_test_electrahub\",\"keySecret\":\"secret\"}";
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.of("webhook-secret");
            }
        };
    }

    private GatewayConnection connection() {
        return new GatewayConnection(UUID.randomUUID(), GatewayProvider.RAZORPAY, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "razorpay-rest-v1", "razorpay-sandbox", Set.of(), true, true,
                false, null, null, null, 1, Instant.now(), Instant.now());
    }

    private GatewayOperationRequest request() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-123",
                "idem-123", GatewayOperationType.AUTHORIZE, new BigDecimal("25.00"), "INR", "account-123", null, null,
                "https://driver.electrahub.net/payments/return", Instant.now());
    }

    private String hmac(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).startsWith("Basic ");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
