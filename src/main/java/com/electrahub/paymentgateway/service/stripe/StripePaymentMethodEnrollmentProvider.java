package com.electrahub.paymentgateway.service.stripe;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import com.electrahub.paymentgateway.service.spi.PaymentMethodEnrollmentProvider;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Locale;
import java.util.UUID;

@Component
public class StripePaymentMethodEnrollmentProvider implements PaymentMethodEnrollmentProvider {

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String publishableKey;

    public StripePaymentMethodEnrollmentProvider(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.stripe.base-url:https://api.stripe.com}") String baseUrl,
            @Value("${app.gateway.providers.stripe.publishable-key:${APP_GATEWAY_STRIPE_PUBLISHABLE_KEY:}}")
            String publishableKey
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.publishableKey = publishableKey == null ? "" : publishableKey.trim();
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.STRIPE;
    }

    @Override
    public ProviderCustomer createCustomer(GatewayConnection connection, String accountReferenceHash) {
        validateConfiguration(connection);
        Map<String, String> form = Map.of(
                "description", "ElectraHub driver payment profile",
                "metadata[electrahub_account_hash]", accountReferenceHash
        );
        JsonNode body = requireSuccessful(transport.postForm(
                uri("/v1/customers"),
                authorization(secretKey(connection), "electrahub-customer-" + connection.id() + "-" + accountReferenceHash),
                form
        ));
        String reference = body.path("id").asText();
        if (!reference.matches("^cus_[A-Za-z0-9_]+$")) {
            throw new GatewayUnavailableException("STRIPE_CUSTOMER_RESPONSE_INVALID", "Stripe returned an invalid Customer.");
        }
        return new ProviderCustomer(reference);
    }

    @Override
    public ProviderEnrollment start(
            GatewayConnection connection,
            String providerCustomerReference,
            UUID enrollmentId,
            String countryCode,
            String currency,
            String returnUrl
    ) {
        validateConfiguration(connection);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("customer", requireCustomer(providerCustomerReference));
        form.put("payment_method_types[]", "card");
        form.put("usage", "off_session");
        form.put("metadata[electrahub_enrollment_id]", enrollmentId.toString());

        JsonNode body = requireSuccessful(transport.postForm(
                uri("/v1/setup_intents"),
                authorization(secretKey(connection), "electrahub-enrollment-" + enrollmentId),
                form
        ));
        String reference = body.path("id").asText();
        String clientSecret = body.path("client_secret").asText();
        if (!reference.startsWith("seti_") || !clientSecret.startsWith(reference + "_secret_")) {
            throw new GatewayUnavailableException("STRIPE_SETUP_INTENT_RESPONSE_INVALID", "Stripe returned an invalid SetupIntent.");
        }
        return new ProviderEnrollment(
                reference,
                clientSecret,
                publishableKey,
                Instant.now().plus(30, ChronoUnit.MINUTES)
        );
    }

    @Override
    public ProviderEnrollmentResult retrieve(
            GatewayConnection connection,
            String providerEnrollmentReference,
            String providerResult
    ) {
        validateConfiguration(connection);
        String setupIntentReference = requireSetupIntent(providerEnrollmentReference);
        JsonNode setupIntent = requireSuccessful(transport.get(
                uri("/v1/setup_intents/" + setupIntentReference),
                authorization(secretKey(connection), null)
        ));
        String status = setupIntent.path("status").asText("unknown");
        String customer = setupIntent.path("customer").asText(null);
        String paymentMethodReference = setupIntent.path("payment_method").asText(null);
        if (!"succeeded".equals(status)) {
            return new ProviderEnrollmentResult(status, paymentMethodReference, customer, null, null, 0, 0);
        }
        if (paymentMethodReference == null || !paymentMethodReference.matches("^pm_[A-Za-z0-9_]+$")) {
            throw new GatewayUnavailableException("STRIPE_PAYMENT_METHOD_RESPONSE_INVALID", "Stripe returned no reusable card.");
        }
        JsonNode paymentMethod = requireSuccessful(transport.get(
                uri("/v1/payment_methods/" + paymentMethodReference),
                authorization(secretKey(connection), null)
        ));
        JsonNode card = paymentMethod.path("card");
        if (!"card".equals(paymentMethod.path("type").asText()) || card.isMissingNode()) {
            throw new GatewayBusinessException("PAYMENT_METHOD_TYPE_UNSUPPORTED", "The enrolled payment method is not a card.");
        }
        return new ProviderEnrollmentResult(
                status,
                paymentMethodReference,
                customer,
                card.path("brand").asText("card").toUpperCase(Locale.ROOT),
                card.path("last4").asText(),
                card.path("exp_month").asInt(),
                card.path("exp_year").asInt() % 100
        );
    }

    private void validateConfiguration(GatewayConnection connection) {
        String profile = connection.endpointProfile() == null ? "" : connection.endpointProfile().trim().toLowerCase(Locale.ROOT);
        boolean sandbox = profile.equals("sandbox") || profile.equals("stripe-sandbox");
        boolean production = profile.equals("production") || profile.equals("stripe-production");
        if ((connection.environment() == GatewayEnvironment.SANDBOX && !sandbox)
                || (connection.environment() == GatewayEnvironment.PRODUCTION && !production)) {
            throw new GatewayBusinessException("STRIPE_ENDPOINT_PROFILE_INVALID", "Stripe endpoint profile does not match the connection environment.");
        }
        boolean validPublishableKey = connection.environment() == GatewayEnvironment.SANDBOX
                ? publishableKey.startsWith("pk_test_")
                : publishableKey.startsWith("pk_live_");
        if (!validPublishableKey) {
            throw new GatewayBusinessException("STRIPE_PUBLISHABLE_KEY_INVALID", "Stripe card enrollment is not configured.");
        }
    }

    private String secretKey(GatewayConnection connection) {
        String credential = credentialResolver.requireCredential(connection);
        String key = credential;
        if (credential.startsWith("{")) {
            key = json(credential).path("secretKey").asText();
        }
        boolean testKey = key != null && (key.startsWith("sk_test_") || key.startsWith("rk_test_") || key.startsWith("rkcs_test_"));
        boolean liveKey = key != null && (key.startsWith("sk_live_") || key.startsWith("rk_live_"));
        if ((connection.environment() == GatewayEnvironment.SANDBOX && !testKey)
                || (connection.environment() == GatewayEnvironment.PRODUCTION && !liveKey)) {
            throw new GatewayBusinessException("STRIPE_CREDENTIAL_INVALID", "Stripe enrollment credentials are invalid.");
        }
        return key;
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        if (response.successful()) {
            return json(response.body());
        }
        JsonNode error = json(response.body()).path("error");
        String code = error.path("code").asText("STRIPE_REQUEST_FAILED");
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            throw new GatewayBusinessException("STRIPE_" + boundedCode(code), "Stripe rejected the card enrollment request.");
        }
        throw new GatewayUnavailableException("STRIPE_UNAVAILABLE_" + response.statusCode(), "Stripe is temporarily unavailable.");
    }

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException exception) {
            throw new GatewayUnavailableException("PAYMENT_PROVIDER_RESPONSE_INVALID", "Stripe returned an invalid response.");
        }
    }

    private Map<String, String> authorization(String key, String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + key);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put("Idempotency-Key", idempotencyKey);
        }
        return headers;
    }

    private String requireCustomer(String value) {
        if (value == null || !value.matches("^cus_[A-Za-z0-9_]+$")) {
            throw new GatewayBusinessException("STRIPE_CUSTOMER_REQUIRED", "A Stripe Customer reference is required.");
        }
        return value;
    }

    private String requireSetupIntent(String value) {
        if (value == null || !value.matches("^seti_[A-Za-z0-9_]+$")) {
            throw new GatewayBusinessException("STRIPE_SETUP_INTENT_REQUIRED", "A Stripe SetupIntent reference is required.");
        }
        return value;
    }

    private String boundedCode(String value) {
        String normalized = value.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        return normalized.length() <= 48 ? normalized : normalized.substring(0, 48);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
