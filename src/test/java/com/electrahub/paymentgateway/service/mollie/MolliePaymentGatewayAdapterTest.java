package com.electrahub.paymentgateway.service.mollie;

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
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class MolliePaymentGatewayAdapterTest {

    private HttpServer server;
    private MolliePaymentGatewayAdapter adapter;
    private boolean paymentAlreadyPaid;
    private int captureRequests;
    private String captureStatus = "succeeded";
    private String refundStatus = "queued";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v2/payments", this::payments);
        server.start();
        ProviderCredentialResolver resolver = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return "test_electrahub";
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        adapter = new MolliePaymentGatewayAdapter(
                new ProviderHttpTransport(), resolver, new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "https://api.electrahub.net/payment-gateway/api/v1/gateway/webhooks/connection"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsManualCaptureHostedCheckout() {
        assertThat(adapter.validate(connection()).valid()).isTrue();

        var result = adapter.execute(request(), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isEqualTo("tr_test123");
        assertThat(result.action().url()).isEqualTo("https://checkout.mollie.test/tr_test123");
    }

    @Test
    void rejectsNonEuroSandboxMutationBeforeCallingMollie() {
        GatewayOperationRequest usdRequest = new GatewayOperationRequest(
                UUID.randomUUID(), "payment-intent-usd", null, "operation-usd", "idem-usd",
                GatewayOperationType.AUTHORIZE, new BigDecimal("25.00"), "USD", "account-123",
                null, null, null, "https://driver.electrahub.net/payments/return", Instant.now()
        );

        assertThatThrownBy(() -> adapter.execute(usdRequest, connection()))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("MOLLIE_SANDBOX_EUR_REQUIRED")
                );
    }

    @Test
    void doesNotCaptureAnAlreadyPaidPaymentAgain() {
        paymentAlreadyPaid = true;

        var result = adapter.execute(captureRequest(), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(captureRequests).isZero();
    }

    @Test
    void reconcilesACaptureByCaptureIdInsteadOfThePaidParentPayment() {
        captureStatus = "pending";
        var pending = adapter.execute(captureRequest(), connection());
        paymentAlreadyPaid = true;

        var reconciled = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-456", "idem-456", pending.providerReference(),
                GatewayOperationType.CAPTURE, pending.publicTransactionReference()
        ), connection());

        assertThat(pending.publicTransactionReference()).isEqualTo("cpt_test123");
        assertThat(reconciled.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(reconciled.publicTransactionReference()).isEqualTo("cpt_test123");
    }

    @Test
    void reconcilesARefundByRefundIdInsteadOfThePaidParentPayment() {
        var pending = adapter.execute(refundRequest(), connection());
        paymentAlreadyPaid = true;

        var reconciled = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-789", "idem-789", pending.providerReference(),
                GatewayOperationType.REFUND, pending.publicTransactionReference()
        ), connection());

        assertThat(pending.publicTransactionReference()).isEqualTo("re_test123");
        assertThat(reconciled.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(reconciled.publicTransactionReference()).isEqualTo("re_test123");
    }

    @Test
    void releasesAuthorizationAsynchronouslyWithAnIdempotencyKey() {
        var result = adapter.execute(voidRequest(), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(result.code()).isEqualTo("MOLLIE_RELEASE_PENDING");
        assertThat(result.providerReference()).isEqualTo("tr_test123");
    }

    @Test
    void doesNotTreatCapturedFundsAsASuccessfulAuthorizationRelease() {
        paymentAlreadyPaid = true;

        var result = adapter.queryStatus(new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-void", "idem-void", "tr_test123",
                GatewayOperationType.VOID, "tr_test123"
        ), connection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.FAILED);
        assertThat(result.code()).isEqualTo("MOLLIE_RELEASE_FAILED");
    }

    @Test
    void authenticatesClassicWebhookByRefetchingPayment() {
        var event = adapter.parseWebhook(connection(), "id=tr_test123", java.util.Map.of());

        assertThat(event.providerReference()).isEqualTo("tr_test123");
        assertThat(event.merchantReference()).isEqualTo("payment-intent-123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.AUTHORIZED);
    }

    private void payments(HttpExchange exchange) throws IOException {
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test_electrahub");
        String path = exchange.getRequestURI().getPath();
        String body;
        if (path.equals("/v2/payments") && exchange.getRequestMethod().equals("GET")) {
            body = "{\"_embedded\":{\"payments\":[]}}";
        } else if (path.equals("/v2/payments") && exchange.getRequestMethod().equals("POST")) {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-123");
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(request).contains("\"method\":\"creditcard\"");
            assertThat(request).contains("\"captureMode\":\"manual\"");
            body = "{\"id\":\"tr_test123\",\"status\":\"open\",\"_links\":{\"checkout\":{\"href\":\"https://checkout.mollie.test/tr_test123\"}}}";
        } else if (path.equals("/v2/payments/tr_test123/captures") && exchange.getRequestMethod().equals("POST")) {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-456");
            captureRequests++;
            body = "{\"id\":\"cpt_test123\",\"status\":\"" + captureStatus + "\"}";
        } else if (path.equals("/v2/payments/tr_test123/captures/cpt_test123")) {
            body = "{\"id\":\"cpt_test123\",\"status\":\"" + captureStatus + "\"}";
        } else if (path.equals("/v2/payments/tr_test123/refunds") && exchange.getRequestMethod().equals("POST")) {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-789");
            body = "{\"id\":\"re_test123\",\"paymentId\":\"tr_test123\",\"status\":\"" + refundStatus + "\"}";
        } else if (path.equals("/v2/payments/tr_test123/refunds/re_test123")) {
            body = "{\"id\":\"re_test123\",\"paymentId\":\"tr_test123\",\"status\":\"" + refundStatus + "\"}";
        } else if (path.equals("/v2/payments/tr_test123/release-authorization")
                && exchange.getRequestMethod().equals("POST")) {
            assertThat(exchange.getRequestHeaders().getFirst("Idempotency-Key")).isEqualTo("idem-void");
            respond(exchange, 202, "");
            return;
        } else {
            body = paymentAlreadyPaid
                    ? """
                    {"id":"tr_test123","status":"paid","paidAt":"2026-07-31T14:00:00Z",
                     "metadata":{"electrahub_payment_intent_id":"payment-intent-123"}}
                    """
                    : """
                    {"id":"tr_test123","status":"authorized","authorizedAt":"2026-07-31T14:00:00Z",
                     "metadata":{"electrahub_payment_intent_id":"payment-intent-123"}}
                    """;
        }
        respond(exchange, body);
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }

    private GatewayConnection connection() {
        return new GatewayConnection(UUID.randomUUID(), GatewayProvider.MOLLIE, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY, "mollie-rest-v2", "mollie-sandbox", Set.of(), true, false,
                false, null, null, null, 1, Instant.now(), Instant.now());
    }

    private GatewayOperationRequest request() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-123",
                "idem-123", GatewayOperationType.AUTHORIZE, new BigDecimal("25.00"), "EUR", "account-123", null, null, null,
                "https://driver.electrahub.net/payments/return", Instant.now());
    }

    private GatewayOperationRequest captureRequest() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-456",
                "idem-456", GatewayOperationType.CAPTURE, new BigDecimal("25.00"), "EUR", "account-123",
                null, null, "tr_test123", null, Instant.now());
    }

    private GatewayOperationRequest refundRequest() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-789",
                "idem-789", GatewayOperationType.REFUND, new BigDecimal("5.00"), "EUR", "account-123",
                null, null, "tr_test123", null, Instant.now());
    }

    private GatewayOperationRequest voidRequest() {
        return new GatewayOperationRequest(UUID.randomUUID(), "payment-intent-123", null, "operation-void",
                "idem-void", GatewayOperationType.VOID, new BigDecimal("25.00"), "EUR", "account-123",
                null, null, "tr_test123", null, Instant.now());
    }
}
