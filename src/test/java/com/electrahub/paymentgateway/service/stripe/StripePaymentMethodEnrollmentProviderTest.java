package com.electrahub.paymentgateway.service.stripe;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class StripePaymentMethodEnrollmentProviderTest {

    private HttpServer server;
    private StripePaymentMethodEnrollmentProvider provider;
    private final AtomicReference<String> customerRequest = new AtomicReference<>();
    private final AtomicReference<String> setupIntentRequest = new AtomicReference<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/customers", this::customer);
        server.createContext("/v1/setup_intents", this::setupIntent);
        server.createContext("/v1/payment_methods", this::paymentMethod);
        server.start();
        ProviderCredentialResolver credentials = new ProviderCredentialResolver() {
            @Override
            public String requireCredential(GatewayConnection connection) {
                return "sk_test_electrahub";
            }

            @Override
            public Optional<String> webhookSecret(GatewayConnection connection) {
                return Optional.empty();
            }
        };
        provider = new StripePaymentMethodEnrollmentProvider(
                new ProviderHttpTransport(),
                credentials,
                new ObjectMapper(),
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "pk_test_electrahub"
        );
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void createsCustomerAndSetupIntentThenRetrievesReusableCard() {
        UUID enrollmentId = UUID.randomUUID();

        var customer = provider.createCustomer(connection(), "account-hash");
        var enrollment = provider.start(
                connection(), customer.reference(), enrollmentId, "US", "USD", "electrahub://payment/card-setup"
        );
        var result = provider.retrieve(connection(), enrollment.reference(), null);

        assertThat(customer.reference()).isEqualTo("cus_test");
        assertThat(enrollment.clientSecret()).isEqualTo("seti_test_secret_123");
        assertThat(enrollment.publishableKey()).isEqualTo("pk_test_electrahub");
        assertThat(customerRequest.get()).contains("metadata%5Belectrahub_account_hash%5D=account-hash");
        assertThat(setupIntentRequest.get())
                .contains("customer=cus_test")
                .contains("usage=off_session")
                .contains("payment_method_types%5B%5D=card")
                .doesNotContain("automatic_payment_methods")
                .doesNotContain("return_url");
        assertThat(result.status()).isEqualTo("succeeded");
        assertThat(result.paymentMethodReference()).isEqualTo("pm_test");
        assertThat(result.providerCustomerReference()).isEqualTo("cus_test");
        assertThat(result.brand()).isEqualTo("VISA");
        assertThat(result.last4()).isEqualTo("4242");
        assertThat(result.expiryMonth()).isEqualTo(12);
        assertThat(result.expiryYear()).isEqualTo(30);
    }

    private void customer(HttpExchange exchange) throws IOException {
        customerRequest.set(body(exchange));
        assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer sk_test_electrahub");
        respond(exchange, 200, "{\"id\":\"cus_test\"}");
    }

    private void setupIntent(HttpExchange exchange) throws IOException {
        if ("GET".equals(exchange.getRequestMethod())) {
            respond(exchange, 200, "{\"id\":\"seti_test\",\"status\":\"succeeded\",\"customer\":\"cus_test\",\"payment_method\":\"pm_test\"}");
            return;
        }
        setupIntentRequest.set(body(exchange));
        respond(exchange, 200, "{\"id\":\"seti_test\",\"client_secret\":\"seti_test_secret_123\",\"status\":\"requires_payment_method\"}");
    }

    private void paymentMethod(HttpExchange exchange) throws IOException {
        respond(exchange, 200, "{\"id\":\"pm_test\",\"type\":\"card\",\"card\":{\"brand\":\"visa\",\"last4\":\"4242\",\"exp_month\":12,\"exp_year\":2030}}");
    }

    private String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
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
                UUID.fromString("79b8e91d-88cd-477e-b8a3-f628c0fab9e2"),
                GatewayProvider.STRIPE,
                GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.ACTIVE,
                "stripe-rest-v1",
                "stripe-sandbox",
                Set.of(),
                true,
                true,
                false,
                null,
                null,
                null,
                1,
                Instant.now(),
                Instant.now()
        );
    }
}
