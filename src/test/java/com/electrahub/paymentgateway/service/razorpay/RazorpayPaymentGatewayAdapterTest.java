package com.electrahub.paymentgateway.service.razorpay;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
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
    private String refundStatus = "pending";
    private String paymentStatus = "captured";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/payments", this::payments);
        server.createContext("/v1/refunds", this::refunds);
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
        var validation = adapter.validate(connection());
        assertThat(validation.valid()).isTrue();
        assertThat(validation.capabilities())
                .contains(GatewayCapability.AUTHORIZE, GatewayCapability.MANUAL_CAPTURE, GatewayCapability.CAPTURE)
                .doesNotContain(GatewayCapability.VOID);

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

    @Test
    void reconcilesRefundUsingTheRefundIdInsteadOfTheCapturedParentPayment() {
        var pending = adapter.execute(refundRequest(), connection());
        paymentStatus = "captured";

        var reconciled = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-refund", "idem-refund", pending.providerReference(),
                GatewayOperationType.REFUND, pending.publicTransactionReference()
        ), connection());

        assertThat(pending.providerReference()).isEqualTo("pay_test123");
        assertThat(pending.publicTransactionReference()).isEqualTo("rfnd_test123");
        assertThat(reconciled.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);

        refundStatus = "processed";
        assertThat(adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-refund", "idem-refund", pending.providerReference(),
                GatewayOperationType.REFUND, pending.publicTransactionReference()
        ), connection()).status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
    }

    @Test
    void waitsForRazorpayAutomaticReleaseWithoutClaimingVoidCapability() {
        var pending = adapter.execute(voidRequest(), connection());
        assertThat(pending.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(pending.code()).isEqualTo("RAZORPAY_AUTO_RELEASE_PENDING");

        paymentStatus = "refunded";
        var released = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-void", "idem-void", pending.providerReference(),
                GatewayOperationType.VOID, pending.publicTransactionReference()
        ), connection());

        assertThat(released.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(released.code()).isEqualTo("RAZORPAY_AUTO_RELEASED");
    }

    @Test
    void treatsAFailedUncapturedPaymentAsHavingNoAuthorizationLeftToRelease() {
        paymentStatus = "failed";

        var released = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-void", "idem-void", "pay_test123",
                GatewayOperationType.VOID, "pay_test123"
        ), connection());

        assertThat(released.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(released.code()).isEqualTo("RAZORPAY_AUTO_RELEASED");
    }

    private void payments(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/v1/payments".equals(path)) {
            respond(exchange, 200, "{\"entity\":\"collection\",\"items\":[]}");
        } else if ("/v1/payments/pay_test123/refund".equals(path) && "POST".equals(exchange.getRequestMethod())) {
            assertThat(exchange.getRequestHeaders().getFirst("X-Refund-Idempotency")).isEqualTo("idem-refund");
            respond(exchange, 200, "{\"id\":\"rfnd_test123\",\"payment_id\":\"pay_test123\",\"status\":\""
                    + refundStatus + "\"}");
        } else if ("/v1/payments/pay_test123".equals(path)) {
            respond(exchange, 200, "{\"id\":\"pay_test123\",\"order_id\":\"order_test123\",\"status\":\""
                    + paymentStatus + "\"}");
        } else {
            respond(exchange, 404, "{}");
        }
    }

    private void refunds(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"id\":\"rfnd_test123\",\"payment_id\":\"pay_test123\",\"status\":\""
                + refundStatus + "\"}");
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
                "idem-123", GatewayOperationType.AUTHORIZE, new BigDecimal("25.00"), "INR", "account-123", null, null, null,
                "https://driver.electrahub.net/payments/return", Instant.now());
    }

    private GatewayOperationRequest refundRequest() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-refund",
                "idem-refund", GatewayOperationType.REFUND, new BigDecimal("5.00"), "INR", "account-123",
                null, null, "pay_test123", null, Instant.now());
    }

    private GatewayOperationRequest voidRequest() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-void",
                "idem-void", GatewayOperationType.VOID, new BigDecimal("25.00"), "INR", "account-123",
                null, null, "pay_test123", null, Instant.now());
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
