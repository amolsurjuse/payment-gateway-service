package com.electrahub.paymentgateway.service.adyen;

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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Adyen zero-value authorization flow for reusable, shopper-bound card tokens. */
@Component
public class AdyenPaymentMethodEnrollmentProvider implements PaymentMethodEnrollmentProvider {

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final String testBaseUrl;

    public AdyenPaymentMethodEnrollmentProvider(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.adyen.test-base-url:https://checkout-test.adyen.com/v72}") String testBaseUrl
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.testBaseUrl = stripTrailingSlash(testBaseUrl);
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.ADYEN;
    }

    @Override
    public ProviderCustomer createCustomer(GatewayConnection connection, String accountReferenceHash) {
        credentials(connection);
        return new ProviderCustomer("eh_" + accountReferenceHash);
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
        Credentials credentials = credentials(connection);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", credentials.merchantAccount());
        body.put("amount", Map.of("currency", currency.toUpperCase(Locale.ROOT), "value", 0));
        body.put("reference", "electrahub-enrollment-" + enrollmentId);
        body.put("returnUrl", returnUrl);
        body.put("countryCode", requireCountry(countryCode));
        body.put("channel", "iOS");
        body.put("shopperReference", providerCustomerReference);
        body.put("shopperInteraction", "Ecommerce");
        body.put("recurringProcessingModel", "UnscheduledCardOnFile");
        body.put("storePaymentMethod", true);
        body.put("storePaymentMethodMode", "enabled");
        body.put("allowedPaymentMethods", new String[]{"scheme"});
        JsonNode response = requireSuccessful(transport.postJson(
                uri(connection, credentials, "/sessions"),
                headers(credentials, enrollmentId.toString()),
                json(body)
        ));
        String sessionId = response.path("id").asText();
        String sessionData = response.path("sessionData").asText();
        if (sessionId.isBlank() || sessionData.isBlank()) {
            throw new GatewayUnavailableException("ADYEN_SESSION_INVALID", "Adyen returned an invalid enrollment session.");
        }
        Instant expiresAt = parseInstant(response.path("expiresAt").asText());
        return new ProviderEnrollment(sessionId, sessionData, credentials.clientKey(), expiresAt);
    }

    @Override
    public ProviderEnrollmentResult retrieve(
            GatewayConnection connection,
            String providerEnrollmentReference,
            String providerResult
    ) {
        if (providerResult == null || providerResult.isBlank()) {
            throw new GatewayBusinessException("ADYEN_SESSION_RESULT_REQUIRED", "Adyen session result is required.");
        }
        Credentials credentials = credentials(connection);
        String query = URLEncoder.encode(providerResult.trim(), StandardCharsets.UTF_8);
        JsonNode response = requireSuccessful(transport.get(
                uri(connection, credentials, "/sessions/" + requireSession(providerEnrollmentReference)
                        + "?sessionResult=" + query),
                headers(credentials, null)
        ));
        String resultCode = response.path("resultCode").asText(response.path("status").asText("unknown"));
        if (!"authorised".equalsIgnoreCase(resultCode)) {
            return new ProviderEnrollmentResult(resultCode, null, null, null, null, 0, 0);
        }
        JsonNode additional = response.path("additionalData");
        String token = firstText(additional,
                "tokenization.storedPaymentMethodId", "recurring.recurringDetailReference", "storedPaymentMethodId");
        String shopper = firstText(additional, "tokenization.shopperReference", "shopperReference");
        String last4 = firstText(additional, "cardSummary", "card.lastFour");
        String expiry = firstText(additional, "expiryDate", "card.expiryDate");
        String brand = firstText(additional, "paymentMethod", "cardPaymentMethod");
        if (token == null || shopper == null || last4 == null || expiry == null) {
            throw new GatewayUnavailableException("ADYEN_TOKEN_RESPONSE_INVALID", "Adyen returned no reusable card token.");
        }
        String[] expiryParts = expiry.split("/");
        if (expiryParts.length != 2) {
            throw new GatewayUnavailableException("ADYEN_CARD_RESPONSE_INVALID", "Adyen returned invalid card metadata.");
        }
        return new ProviderEnrollmentResult(
                "succeeded", token, shopper,
                brand == null ? "CARD" : brand.toUpperCase(Locale.ROOT), last4,
                Integer.parseInt(expiryParts[0]), Integer.parseInt(expiryParts[1]) % 100
        );
    }

    private Credentials credentials(GatewayConnection connection) {
        try {
            JsonNode value = objectMapper.readTree(credentialResolver.requireCredential(connection));
            Credentials result = new Credentials(
                    value.path("apiKey").asText(), value.path("merchantAccount").asText(),
                    value.path("clientKey").asText(), value.path("countryCode").asText("NL").toUpperCase(Locale.ROOT),
                    value.path("liveBaseUrl").asText()
            );
            if (result.apiKey().isBlank() || result.merchantAccount().isBlank() || result.clientKey().isBlank()
                    || !result.countryCode().matches("^[A-Z]{2}$")) {
                throw new GatewayBusinessException("ADYEN_CREDENTIAL_INVALID", "Adyen enrollment configuration is incomplete.");
            }
            if (connection.environment() == GatewayEnvironment.PRODUCTION && result.liveBaseUrl().isBlank()) {
                throw new GatewayBusinessException("ADYEN_LIVE_ENDPOINT_REQUIRED", "Adyen live endpoint is required.");
            }
            return result;
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("ADYEN_CREDENTIAL_INVALID", "Adyen credentials must be JSON.");
        }
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        JsonNode body = parse(response.body());
        if (response.successful()) return body;
        if (response.statusCode() >= 400 && response.statusCode() < 500) {
            throw new GatewayBusinessException("ADYEN_ENROLLMENT_REJECTED", "Adyen rejected card enrollment.");
        }
        throw new GatewayUnavailableException("ADYEN_UNAVAILABLE_" + response.statusCode(), "Adyen is temporarily unavailable.");
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = node.path(name).asText();
            if (!value.isBlank()) return value;
        }
        return null;
    }

    private String requireSession(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9_-]{8,160}$")) {
            throw new GatewayBusinessException("ADYEN_SESSION_REQUIRED", "Adyen session is invalid.");
        }
        return value;
    }

    private String requireCountry(String value) {
        String result = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!result.matches("^[A-Z]{2}$")) {
            throw new GatewayBusinessException("ADYEN_COUNTRY_REQUIRED", "Adyen enrollment country is invalid.");
        }
        return result;
    }

    private URI uri(GatewayConnection connection, Credentials credentials, String path) {
        String base = connection.environment() == GatewayEnvironment.SANDBOX ? testBaseUrl : stripTrailingSlash(credentials.liveBaseUrl());
        return URI.create(base + path);
    }

    private Map<String, String> headers(Credentials credentials, String idempotencyKey) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("X-API-Key", credentials.apiKey());
        result.put("Accept", "application/json");
        if (idempotencyKey != null) result.put("Idempotency-Key", idempotencyKey);
        return result;
    }

    private String json(Object value) {
        try { return objectMapper.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("Unable to encode Adyen request.", exception); }
    }

    private JsonNode parse(String value) {
        try { return objectMapper.readTree(value == null || value.isBlank() ? "{}" : value); }
        catch (JsonProcessingException exception) {
            throw new GatewayUnavailableException("ADYEN_RESPONSE_INVALID", "Adyen returned invalid JSON.");
        }
    }

    private Instant parseInstant(String value) {
        try { return Instant.parse(value); }
        catch (RuntimeException exception) { return Instant.now().plusSeconds(3600); }
    }

    private String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private record Credentials(String apiKey, String merchantAccount, String clientKey, String countryCode, String liveBaseUrl) {}
}
