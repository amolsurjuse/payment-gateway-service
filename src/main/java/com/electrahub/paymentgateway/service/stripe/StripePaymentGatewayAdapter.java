package com.electrahub.paymentgateway.service.stripe;

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
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class StripePaymentGatewayAdapter implements PaymentGatewayAdapter {

    private static final Set<GatewayCapability> CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.MANUAL_CAPTURE,
            GatewayCapability.CAPTURE,
            GatewayCapability.PARTIAL_CAPTURE,
            GatewayCapability.VOID,
            GatewayCapability.REFUND,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA
    );

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final long webhookToleranceSeconds;

    public StripePaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.stripe.base-url:https://api.stripe.com}") String baseUrl,
            @Value("${app.gateway.providers.stripe.webhook-tolerance-seconds:300}") long webhookToleranceSeconds
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.webhookToleranceSeconds = webhookToleranceSeconds;
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.STRIPE;
    }

    @Override
    public ConnectionValidation validate(GatewayConnection connection) {
        validateProfile(connection);
        String secretKey = secretKey(connection);
        ProviderHttpTransport.Response response = transport.get(
                uri("/v1/payment_intents?limit=1"),
                authorization(secretKey, null)
        );
        if (response.statusCode() == 401) {
            return invalid("STRIPE_CREDENTIAL_REJECTED", "Stripe rejected the configured credential.");
        }
        if (!response.successful()) {
            throw unavailable(response.statusCode());
        }
        JsonNode body = json(response.body());
        if (!body.path("data").isArray()) {
            return invalid("STRIPE_PERMISSION_RESPONSE_INVALID", "Stripe returned an invalid permission probe response.");
        }
        return new ConnectionValidation(true, "READY", "Stripe connection validated.", CAPABILITIES, "stripe-rest-v1");
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        return switch (request.operationType()) {
            case AUTHORIZE -> authorize(request, connection);
            case CAPTURE -> capture(request, connection);
            case VOID -> cancel(request, connection);
            case REFUND -> refund(request, connection);
            case STATUS_QUERY -> queryStatus(
                    new GatewayOperationStatusQuery(null, request.operationId(), request.idempotencyKey(), requireProviderReference(request)),
                    connection
            );
        };
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        String providerReference = requireProviderReference(query.providerReference());
        ProviderHttpTransport.Response response = transport.get(
                uri("/v1/payment_intents/" + pathSegment(providerReference)),
                authorization(secretKey(connection), null)
        );
        JsonNode body = requireSuccessful(response);
        return paymentIntentResult(query.gatewayOperationId(), body);
    }

    @Override
    public GatewayWebhookEvent parseWebhook(
            GatewayConnection connection,
            String rawBody,
            Map<String, String> headers
    ) {
        validateProfile(connection);
        String webhookSecret = credentialResolver.webhookSecret(connection)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> invalidWebhook("STRIPE_WEBHOOK_SECRET_MISSING", "Stripe webhook verification is not configured."));
        verifyWebhookSignature(rawBody, headers.get("stripe-signature"), webhookSecret);

        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException exception) {
            throw invalidWebhook("STRIPE_WEBHOOK_INVALID", "Stripe webhook payload is invalid.");
        }
        String eventId = requiredWebhookValue(event.path("id").asText(), "STRIPE_WEBHOOK_EVENT_ID_MISSING");
        String eventType = requiredWebhookValue(event.path("type").asText(), "STRIPE_WEBHOOK_TYPE_MISSING");
        JsonNode object = event.path("data").path("object");
        String objectType = object.path("object").asText();
        String providerReference = "payment_intent".equals(objectType)
                ? object.path("id").asText(null)
                : object.path("payment_intent").asText(null);
        String publicReference = "charge".equals(objectType) ? object.path("id").asText(null) : null;
        String merchantReference = object.path("metadata").path("electrahub_payment_intent_id").asText(null);

        GatewayWebhookOutcome outcome = switch (eventType) {
            case "payment_intent.amount_capturable_updated" -> GatewayWebhookOutcome.AUTHORIZED;
            case "payment_intent.succeeded" -> GatewayWebhookOutcome.CAPTURED;
            case "payment_intent.canceled" -> GatewayWebhookOutcome.VOIDED;
            case "payment_intent.payment_failed" -> GatewayWebhookOutcome.DECLINED;
            case "payment_intent.requires_action" -> GatewayWebhookOutcome.ACTION_REQUIRED;
            case "charge.refunded" -> GatewayWebhookOutcome.REFUNDED;
            default -> GatewayWebhookOutcome.PENDING;
        };
        long created = event.path("created").asLong(0);
        return new GatewayWebhookEvent(
                eventId,
                eventType,
                providerReference,
                merchantReference,
                publicReference,
                outcome,
                codeForWebhook(outcome),
                created > 0 ? Instant.ofEpochSecond(created) : null
        );
    }

    private GatewayOperationResult authorize(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentMethod = requirePaymentMethod(request.paymentMethodReference());
        Map<String, String> form = new LinkedHashMap<>();
        form.put("amount", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())));
        form.put("currency", request.currency().toLowerCase(Locale.ROOT));
        form.put("payment_method", paymentMethod);
        form.put("capture_method", "manual");
        form.put("confirm", "true");
        form.put("metadata[electrahub_operation_id]", request.operationId());
        form.put("metadata[electrahub_payment_intent_id]", request.paymentIntentId());
        if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
            form.put("return_url", request.returnUrl().trim());
        }
        ProviderHttpTransport.Response response = transport.postForm(
                uri("/v1/payment_intents"),
                authorization(secretKey(connection), request.idempotencyKey()),
                form
        );
        return paymentIntentResult(null, requireSuccessful(response));
    }

    private GatewayOperationResult capture(GatewayOperationRequest request, GatewayConnection connection) {
        String providerReference = requireProviderReference(request);
        Map<String, String> form = Map.of(
                "amount_to_capture", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency()))
        );
        ProviderHttpTransport.Response response = transport.postForm(
                uri("/v1/payment_intents/" + pathSegment(providerReference) + "/capture"),
                authorization(secretKey(connection), request.idempotencyKey()),
                form
        );
        return paymentIntentResult(null, requireSuccessful(response));
    }

    private GatewayOperationResult cancel(GatewayOperationRequest request, GatewayConnection connection) {
        String providerReference = requireProviderReference(request);
        ProviderHttpTransport.Response response = transport.postForm(
                uri("/v1/payment_intents/" + pathSegment(providerReference) + "/cancel"),
                authorization(secretKey(connection), request.idempotencyKey()),
                Map.of()
        );
        JsonNode body = requireSuccessful(response);
        return result(
                GatewayOperationStatus.SUCCEEDED,
                "APPROVED",
                body.path("id").asText(providerReference),
                body.path("id").asText(providerReference),
                null
        );
    }

    private GatewayOperationResult refund(GatewayOperationRequest request, GatewayConnection connection) {
        String providerReference = requireProviderReference(request);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("payment_intent", providerReference);
        form.put("amount", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())));
        ProviderHttpTransport.Response response = transport.postForm(
                uri("/v1/refunds"),
                authorization(secretKey(connection), request.idempotencyKey()),
                form
        );
        JsonNode body = requireSuccessful(response);
        String status = body.path("status").asText();
        GatewayOperationStatus gatewayStatus = switch (status) {
            case "succeeded" -> GatewayOperationStatus.SUCCEEDED;
            case "pending", "requires_action" -> GatewayOperationStatus.PENDING_RECONCILIATION;
            case "failed", "canceled" -> GatewayOperationStatus.FAILED;
            default -> GatewayOperationStatus.PENDING_RECONCILIATION;
        };
        return result(gatewayStatus, codeFor(gatewayStatus), body.path("id").asText(), body.path("id").asText(), null);
    }

    private GatewayOperationResult paymentIntentResult(UUID operationId, JsonNode body) {
        String status = body.path("status").asText();
        GatewayOperationStatus gatewayStatus = switch (status) {
            case "requires_capture", "succeeded" -> GatewayOperationStatus.SUCCEEDED;
            case "requires_action", "requires_confirmation" -> GatewayOperationStatus.ACTION_REQUIRED;
            case "processing" -> GatewayOperationStatus.PENDING_RECONCILIATION;
            case "requires_payment_method", "canceled" -> GatewayOperationStatus.DECLINED;
            default -> GatewayOperationStatus.PENDING_RECONCILIATION;
        };
        GatewayAction action = gatewayStatus == GatewayOperationStatus.ACTION_REQUIRED ? action(body) : null;
        String reference = body.path("id").asText();
        return new GatewayOperationResult(
                operationId == null ? UUID.randomUUID() : operationId,
                gatewayStatus,
                codeFor(gatewayStatus),
                reference,
                reference,
                action,
                Instant.now()
        );
    }

    private GatewayAction action(JsonNode body) {
        String redirectUrl = body.path("next_action").path("redirect_to_url").path("url").asText(null);
        return new GatewayAction(
                redirectUrl == null ? GatewayActionType.THREE_DS : GatewayActionType.REDIRECT,
                redirectUrl,
                body.path("client_secret").asText(null),
                null,
                Map.of("provider", "STRIPE")
        );
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        if (response.successful()) {
            return json(response.body());
        }
        JsonNode body = json(response.body());
        String providerCode = body.path("error").path("decline_code").asText();
        if (providerCode.isBlank()) {
            providerCode = body.path("error").path("code").asText("STRIPE_REQUEST_FAILED");
        }
        if (response.statusCode() == 402) {
            throw new GatewayBusinessException("PAYMENT_AUTHORIZATION_DECLINED_" + boundedCode(providerCode), "The payment was declined.");
        }
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 400 || response.statusCode() == 404) {
            throw new GatewayBusinessException("STRIPE_" + boundedCode(providerCode), "Stripe rejected the payment request.");
        }
        throw unavailable(response.statusCode());
    }

    private void verifyWebhookSignature(String rawBody, String signatureHeader, String webhookSecret) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            throw invalidWebhook("STRIPE_WEBHOOK_SIGNATURE_MISSING", "Stripe webhook signature is missing.");
        }
        Long timestamp = null;
        var signatures = new ArrayList<String>();
        for (String part : signatureHeader.split(",")) {
            String[] pair = part.trim().split("=", 2);
            if (pair.length != 2) {
                continue;
            }
            if ("t".equals(pair[0])) {
                try {
                    timestamp = Long.parseLong(pair[1]);
                } catch (NumberFormatException ignored) {
                    throw invalidWebhook("STRIPE_WEBHOOK_SIGNATURE_INVALID", "Stripe webhook signature is invalid.");
                }
            } else if ("v1".equals(pair[0])) {
                signatures.add(pair[1]);
            }
        }
        if (timestamp == null || signatures.isEmpty()) {
            throw invalidWebhook("STRIPE_WEBHOOK_SIGNATURE_INVALID", "Stripe webhook signature is invalid.");
        }
        if (Math.abs(Instant.now().getEpochSecond() - timestamp) > webhookToleranceSeconds) {
            throw invalidWebhook("STRIPE_WEBHOOK_TIMESTAMP_INVALID", "Stripe webhook timestamp is outside the accepted window.");
        }
        String expected = hmacHex(timestamp + "." + rawBody, webhookSecret);
        boolean valid = signatures.stream().anyMatch(candidate -> MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                candidate.getBytes(StandardCharsets.US_ASCII)
        ));
        if (!valid) {
            throw invalidWebhook("STRIPE_WEBHOOK_SIGNATURE_INVALID", "Stripe webhook signature is invalid.");
        }
    }

    private String hmacHex(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw invalidWebhook("STRIPE_WEBHOOK_VERIFICATION_FAILED", "Stripe webhook verification failed.");
        }
    }

    private String requiredWebhookValue(String value, String code) {
        if (value == null || value.isBlank()) {
            throw invalidWebhook(code, "Stripe webhook payload is missing required event data.");
        }
        return value;
    }

    private String codeForWebhook(GatewayWebhookOutcome outcome) {
        return switch (outcome) {
            case AUTHORIZED -> "AUTHORIZED";
            case CAPTURED -> "CAPTURED";
            case VOIDED -> "VOIDED";
            case REFUNDED -> "REFUNDED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            case ACTION_REQUIRED -> "PAYMENT_CUSTOMER_ACTION_REQUIRED";
            case PENDING -> "PROVIDER_STATUS_PENDING";
        };
    }

    private GatewayWebhookVerificationException invalidWebhook(String code, String message) {
        return new GatewayWebhookVerificationException(code, message);
    }

    private String secretKey(GatewayConnection connection) {
        String credential = credentialResolver.requireCredential(connection);
        String key = credential;
        if (credential.startsWith("{")) {
            key = json(credential).path("secretKey").asText();
        }
        if (key == null || key.isBlank()) {
            throw new GatewayBusinessException("STRIPE_CREDENTIAL_INVALID", "Stripe secret key is missing.");
        }
        boolean testKey = key.startsWith("sk_test_") || key.startsWith("rk_test_") || key.startsWith("rkcs_test_");
        boolean liveKey = key.startsWith("sk_live_") || key.startsWith("rk_live_");
        if (connection.environment() == GatewayEnvironment.SANDBOX && !testKey) {
            throw new GatewayBusinessException("STRIPE_SANDBOX_KEY_REQUIRED", "A Stripe test key is required for a sandbox connection.");
        }
        if (connection.environment() == GatewayEnvironment.PRODUCTION && !liveKey) {
            throw new GatewayBusinessException("STRIPE_LIVE_KEY_REQUIRED", "A Stripe live key is required for a production connection.");
        }
        return key;
    }

    private void validateProfile(GatewayConnection connection) {
        String profile = connection.endpointProfile() == null ? "" : connection.endpointProfile().trim().toLowerCase(Locale.ROOT);
        boolean sandbox = profile.equals("sandbox") || profile.equals("stripe-sandbox");
        boolean production = profile.equals("production") || profile.equals("stripe-production");
        if ((connection.environment() == GatewayEnvironment.SANDBOX && !sandbox)
                || (connection.environment() == GatewayEnvironment.PRODUCTION && !production)) {
            throw new GatewayBusinessException("STRIPE_ENDPOINT_PROFILE_INVALID", "Stripe endpoint profile does not match the connection environment.");
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

    private JsonNode json(String body) {
        try {
            return objectMapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (JsonProcessingException exception) {
            throw new GatewayUnavailableException("PAYMENT_PROVIDER_RESPONSE_INVALID", "Stripe returned an invalid response.");
        }
    }

    private ConnectionValidation invalid(String code, String message) {
        return new ConnectionValidation(false, code, message, Set.of(), "stripe-rest-v1");
    }

    private GatewayUnavailableException unavailable(int statusCode) {
        return new GatewayUnavailableException("STRIPE_UNAVAILABLE_" + statusCode, "Stripe is temporarily unavailable.");
    }

    private GatewayOperationResult result(
            GatewayOperationStatus status,
            String code,
            String providerReference,
            String publicReference,
            GatewayAction action
    ) {
        return new GatewayOperationResult(UUID.randomUUID(), status, code, providerReference, publicReference, action, Instant.now());
    }

    private String codeFor(GatewayOperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> "APPROVED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            case ACTION_REQUIRED -> "PAYMENT_CUSTOMER_ACTION_REQUIRED";
            case PENDING_RECONCILIATION -> "PROVIDER_STATUS_PENDING";
            case FAILED -> "PAYMENT_PROVIDER_FAILED";
        };
    }

    private String requirePaymentMethod(String reference) {
        if (reference == null || reference.isBlank() || !reference.startsWith("pm_")) {
            throw new GatewayBusinessException("STRIPE_PAYMENT_METHOD_REQUIRED", "A Stripe PaymentMethod token is required.");
        }
        return reference.trim();
    }

    private String requireProviderReference(GatewayOperationRequest request) {
        return requireProviderReference(request.providerReference());
    }

    private String requireProviderReference(String reference) {
        if (reference == null || reference.isBlank() || !reference.startsWith("pi_")) {
            throw new GatewayBusinessException("STRIPE_PAYMENT_INTENT_REQUIRED", "A Stripe PaymentIntent reference is required.");
        }
        return reference.trim();
    }

    private String pathSegment(String value) {
        if (!value.matches("^[A-Za-z0-9_]+$")) {
            throw new GatewayBusinessException("STRIPE_REFERENCE_INVALID", "Stripe reference is invalid.");
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

    private static String stripTrailingSlash(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
