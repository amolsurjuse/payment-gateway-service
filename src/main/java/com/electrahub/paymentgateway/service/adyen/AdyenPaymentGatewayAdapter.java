package com.electrahub.paymentgateway.service.adyen;

import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayAction;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayActionType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookEvent;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.service.provider.CurrencyMinorUnits;
import com.electrahub.paymentgateway.service.provider.ProviderHttpTransport;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class AdyenPaymentGatewayAdapter implements PaymentGatewayAdapter {

    private static final Set<GatewayCapability> CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.MANUAL_CAPTURE,
            GatewayCapability.CAPTURE,
            GatewayCapability.PARTIAL_CAPTURE,
            GatewayCapability.VOID,
            GatewayCapability.REFUND,
            GatewayCapability.THREE_DS_SCA
    );

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final String testBaseUrl;

    public AdyenPaymentGatewayAdapter(
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
    public ConnectionValidation validate(GatewayConnection connection) {
        validateProfile(connection);
        String webhookSecret = credentialResolver.webhookSecret(connection)
                .filter(value -> !value.isBlank())
                .orElse(null);
        if (!validHmacKey(webhookSecret)) {
            return new ConnectionValidation(
                    false,
                    "ADYEN_WEBHOOK_SECRET_REQUIRED",
                    "A valid hexadecimal Adyen webhook HMAC secret is required because payment results are webhook-driven.",
                    Set.of(),
                    "adyen-checkout-v72"
            );
        }
        Credentials credentials = credentials(connection);
        if (!credentials.manualCaptureEnabled()) {
            return new ConnectionValidation(
                    false,
                    "ADYEN_MANUAL_CAPTURE_REQUIRED",
                    "Enable manual capture for the Adyen merchant account before activation.",
                    Set.of(),
                    "adyen-checkout-v72"
            );
        }
        Map<String, Object> request = Map.of(
                "merchantAccount", credentials.merchantAccount(),
                "countryCode", credentials.countryCode(),
                "amount", Map.of("currency", "EUR", "value", 100)
        );
        ProviderHttpTransport.Response response = transport.postJson(
                uri(connection, credentials, "/paymentMethods"),
                headers(credentials, null),
                json(request)
        );
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            return new ConnectionValidation(false, "ADYEN_CREDENTIAL_REJECTED", "Adyen rejected the configured credential.", Set.of(), "adyen-checkout-v72");
        }
        if (!response.successful()) {
            throw unavailable(response.statusCode());
        }
        return new ConnectionValidation(true, "READY", "Adyen connection validated.", CAPABILITIES, "adyen-checkout-v72");
    }

    private boolean validHmacKey(String value) {
        if (value == null || value.length() != 64) {
            return false;
        }
        try {
            HexFormat.of().parseHex(value);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        return switch (request.operationType()) {
            case AUTHORIZE -> createSession(request, connection);
            case CAPTURE -> modification(request, connection, "captures");
            case VOID -> modification(request, connection, "cancels");
            case REFUND -> modification(request, connection, "refunds");
            case STATUS_QUERY -> queryStatus(new GatewayOperationStatusQuery(
                    null, request.operationId(), request.idempotencyKey(), request.providerReference()), connection);
        };
    }

    /** Adyen's final financial result is webhook-driven; no generic payment-status lookup exists. */
    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        return new GatewayOperationResult(
                query.gatewayOperationId() == null ? UUID.randomUUID() : query.gatewayOperationId(),
                GatewayOperationStatus.PENDING_RECONCILIATION,
                "ADYEN_WEBHOOK_RESULT_PENDING",
                query.providerReference(),
                query.providerReference(),
                null,
                Instant.now()
        );
    }

    @Override
    public List<GatewayWebhookEvent> parseWebhooks(
            GatewayConnection connection,
            String rawBody,
            Map<String, String> headers
    ) {
        String hmacKey = credentialResolver.webhookSecret(connection)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> invalidWebhook("ADYEN_WEBHOOK_SECRET_MISSING", "Adyen webhook verification is not configured."));
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException exception) {
            throw invalidWebhook("ADYEN_WEBHOOK_INVALID", "Adyen webhook payload is invalid.");
        }
        JsonNode notifications = root.path("notificationItems");
        if (!notifications.isArray() || notifications.isEmpty()) {
            throw invalidWebhook("ADYEN_WEBHOOK_ITEMS_MISSING", "Adyen webhook has no notification items.");
        }

        List<GatewayWebhookEvent> events = new ArrayList<>();
        for (JsonNode wrapper : notifications) {
            JsonNode item = wrapper.path("NotificationRequestItem");
            verifyHmac(item, hmacKey);
            String pspReference = required(item.path("pspReference").asText(), "ADYEN_WEBHOOK_REFERENCE_MISSING");
            String originalReference = item.path("originalReference").asText(null);
            String merchantReference = item.path("merchantReference").asText(null);
            String eventCode = required(item.path("eventCode").asText(), "ADYEN_WEBHOOK_TYPE_MISSING");
            boolean successful = "true".equalsIgnoreCase(item.path("success").asText());
            GatewayWebhookOutcome outcome = outcome(eventCode, successful);
            String providerReference = originalReference == null || originalReference.isBlank() ? pspReference : originalReference;
            String eventId = String.join(":", "adyen", pspReference, eventCode, Boolean.toString(successful));
            events.add(new GatewayWebhookEvent(
                    bounded(eventId, 160),
                    bounded(eventCode, 96),
                    providerReference,
                    merchantReference,
                    pspReference,
                    outcome,
                    webhookCode(outcome, eventCode),
                    parseInstant(item.path("eventDate").asText(null))
            ));
        }
        return List.copyOf(events);
    }

    private GatewayOperationResult createSession(GatewayOperationRequest request, GatewayConnection connection) {
        Credentials credentials = credentials(connection);
        if (request.returnUrl() == null || request.returnUrl().isBlank()) {
            throw new GatewayBusinessException("ADYEN_RETURN_URL_REQUIRED", "Adyen checkout requires a return URL.");
        }
        String returnUrl = request.returnUrl().trim();
        boolean nativeIosReturn = returnUrl.toLowerCase(Locale.ROOT).startsWith("electrahub://");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", credentials.merchantAccount());
        body.put("amount", amount(request));
        body.put("reference", bounded(request.operationId(), 80));
        body.put("returnUrl", returnUrl);
        body.put("countryCode", credentials.countryCode());
        body.put("shopperLocale", locale(credentials.countryCode()));
        body.put("channel", nativeIosReturn ? "iOS" : "Web");
        JsonNode response = requireSuccessful(transport.postJson(
                uri(connection, credentials, "/sessions"),
                headers(credentials, request.idempotencyKey()),
                json(body)
        ));
        String sessionId = response.path("id").asText();
        String sessionData = response.path("sessionData").asText();
        if (sessionId.isBlank() || sessionData.isBlank()) {
            throw new GatewayUnavailableException("ADYEN_SESSION_INVALID", "Adyen did not return a checkout session.");
        }
        Map<String, String> actionData = new LinkedHashMap<>();
        actionData.put("provider", "ADYEN");
        actionData.put("sessionId", sessionId);
        actionData.put("clientKey", credentials.clientKey());
        actionData.put("environment", connection.environment() == GatewayEnvironment.SANDBOX ? "test" : "live");
        actionData.put("countryCode", credentials.countryCode());
        actionData.put("amount", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())));
        actionData.put("currency", request.currency().toUpperCase(Locale.ROOT));
        actionData.put("returnUrl", returnUrl);
        return new GatewayOperationResult(
                UUID.randomUUID(),
                GatewayOperationStatus.ACTION_REQUIRED,
                "PAYMENT_CUSTOMER_ACTION_REQUIRED",
                sessionId,
                sessionId,
                new GatewayAction(GatewayActionType.SDK, null, sessionData, parseInstant(response.path("expiresAt").asText(null)), actionData),
                Instant.now()
        );
    }

    private GatewayOperationResult modification(
            GatewayOperationRequest request,
            GatewayConnection connection,
            String operation
    ) {
        Credentials credentials = credentials(connection);
        String pspReference = pspReference(request.providerReference());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("merchantAccount", credentials.merchantAccount());
        body.put("reference", bounded(request.operationId(), 80));
        if (!"cancels".equals(operation)) {
            body.put("amount", amount(request));
        }
        JsonNode response = requireSuccessful(transport.postJson(
                uri(connection, credentials, "/payments/" + pspReference + "/" + operation),
                headers(credentials, request.idempotencyKey()),
                json(body)
        ));
        return new GatewayOperationResult(
                UUID.randomUUID(),
                GatewayOperationStatus.PENDING_RECONCILIATION,
                "PROVIDER_STATUS_PENDING",
                pspReference,
                response.path("pspReference").asText(pspReference),
                null,
                Instant.now()
        );
    }

    private void verifyHmac(JsonNode item, String hmacKeyHex) {
        String signature = item.path("additionalData").path("hmacSignature").asText();
        if (signature.isBlank()) {
            throw invalidWebhook("ADYEN_WEBHOOK_SIGNATURE_MISSING", "Adyen webhook signature is missing.");
        }
        String signedPayload = String.join(":",
                item.path("pspReference").asText(),
                item.path("originalReference").asText(),
                item.path("merchantAccountCode").asText(),
                item.path("merchantReference").asText(),
                item.path("amount").path("value").asText(),
                item.path("amount").path("currency").asText(),
                item.path("eventCode").asText(),
                item.path("success").asText()
        );
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(HexFormat.of().parseHex(hmacKeyHex), "HmacSHA256"));
            String expected = Base64.getEncoder().encodeToString(mac.doFinal(signedPayload.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
                throw invalidWebhook("ADYEN_WEBHOOK_SIGNATURE_INVALID", "Adyen webhook signature is invalid.");
            }
        } catch (IllegalArgumentException exception) {
            throw invalidWebhook("ADYEN_WEBHOOK_SECRET_INVALID", "Adyen webhook verification key is invalid.");
        } catch (GatewayWebhookVerificationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidWebhook("ADYEN_WEBHOOK_VERIFICATION_FAILED", "Adyen webhook verification failed.");
        }
    }

    private GatewayWebhookOutcome outcome(String eventCode, boolean successful) {
        if (!successful) {
            return "AUTHORISATION".equals(eventCode) ? GatewayWebhookOutcome.DECLINED : GatewayWebhookOutcome.PENDING;
        }
        return switch (eventCode) {
            case "AUTHORISATION" -> GatewayWebhookOutcome.AUTHORIZED;
            case "CAPTURE" -> GatewayWebhookOutcome.CAPTURED;
            case "CANCELLATION", "CANCEL_OR_REFUND" -> GatewayWebhookOutcome.VOIDED;
            case "REFUND" -> GatewayWebhookOutcome.REFUNDED;
            default -> GatewayWebhookOutcome.PENDING;
        };
    }

    private String webhookCode(GatewayWebhookOutcome outcome, String eventCode) {
        return switch (outcome) {
            case AUTHORIZED -> "AUTHORIZED";
            case CAPTURED -> "CAPTURED";
            case VOIDED -> "VOIDED";
            case REFUNDED -> "REFUNDED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            default -> "ADYEN_" + eventCode.replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        };
    }

    private Credentials credentials(GatewayConnection connection) {
        String configured = credentialResolver.requireCredential(connection);
        try {
            JsonNode value = objectMapper.readTree(configured);
            Credentials credentials = new Credentials(
                    value.path("apiKey").asText(),
                    value.path("merchantAccount").asText(),
                    value.path("clientKey").asText(),
                    value.path("countryCode").asText("NL").toUpperCase(Locale.ROOT),
                    value.path("liveBaseUrl").asText(),
                    value.path("manualCaptureEnabled").asBoolean(false)
            );
            if (credentials.apiKey().isBlank() || credentials.merchantAccount().isBlank()
                    || credentials.clientKey().isBlank() || !credentials.countryCode().matches("^[A-Z]{2}$")) {
                throw new GatewayBusinessException("ADYEN_CREDENTIAL_INVALID", "Adyen API, merchant, client, or country configuration is missing.");
            }
            if (connection.environment() == GatewayEnvironment.PRODUCTION && credentials.liveBaseUrl().isBlank()) {
                throw new GatewayBusinessException("ADYEN_LIVE_ENDPOINT_REQUIRED", "Adyen live endpoint prefix is required for production.");
            }
            return credentials;
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("ADYEN_CREDENTIAL_INVALID", "Adyen credentials must be a JSON object.");
        }
    }

    private void validateProfile(GatewayConnection connection) {
        String expected = connection.environment() == GatewayEnvironment.SANDBOX ? "adyen-sandbox" : "adyen-production";
        if (!expected.equalsIgnoreCase(connection.endpointProfile())) {
            throw new GatewayBusinessException("ADYEN_ENDPOINT_PROFILE_INVALID", "Adyen endpoint profile does not match the environment.");
        }
    }

    private Map<String, String> headers(Credentials credentials, String idempotencyKey) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("X-API-Key", credentials.apiKey());
        headers.put("Accept", "application/json");
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put("Idempotency-Key", bounded(idempotencyKey, 64));
        }
        return headers;
    }

    private Map<String, Object> amount(GatewayOperationRequest request) {
        return Map.of(
                "currency", request.currency().toUpperCase(Locale.ROOT),
                "value", CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())
        );
    }

    private URI uri(GatewayConnection connection, Credentials credentials, String path) {
        String base = connection.environment() == GatewayEnvironment.SANDBOX
                ? testBaseUrl : stripTrailingSlash(credentials.liveBaseUrl());
        return URI.create(base + path);
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        if (response.successful()) {
            try {
                return objectMapper.readTree(response.body());
            } catch (JsonProcessingException exception) {
                throw new GatewayUnavailableException("ADYEN_RESPONSE_INVALID", "Adyen returned invalid JSON.");
            }
        }
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 404 || response.statusCode() == 422) {
            throw new GatewayBusinessException("ADYEN_REQUEST_REJECTED", "Adyen rejected the payment request.");
        }
        throw unavailable(response.statusCode());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("ADYEN_REQUEST_INVALID", "Unable to create the Adyen request.");
        }
    }

    private String pspReference(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9]{8,64}$")) {
            throw new GatewayBusinessException("ADYEN_PAYMENT_REFERENCE_REQUIRED", "An Adyen PSP reference is required.");
        }
        return value;
    }

    private String locale(String countryCode) {
        return switch (countryCode) {
            case "SE" -> "sv-SE";
            case "DE" -> "de-DE";
            case "GB" -> "en-GB";
            case "NL" -> "nl-NL";
            default -> "en-US";
        };
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String required(String value, String code) {
        if (value == null || value.isBlank()) {
            throw invalidWebhook(code, "Adyen webhook payload is missing required event data.");
        }
        return value;
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private GatewayUnavailableException unavailable(int status) {
        return new GatewayUnavailableException("ADYEN_UNAVAILABLE_" + status, "Adyen is temporarily unavailable.");
    }

    private GatewayWebhookVerificationException invalidWebhook(String code, String message) {
        return new GatewayWebhookVerificationException(code, message);
    }

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    private record Credentials(
            String apiKey,
            String merchantAccount,
            String clientKey,
            String countryCode,
            String liveBaseUrl,
            boolean manualCaptureEnabled
    ) {
    }
}
