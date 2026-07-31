package com.electrahub.paymentgateway.service.razorpay;

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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class RazorpayPaymentGatewayAdapter implements PaymentGatewayAdapter {

    private static final Set<GatewayCapability> CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.MANUAL_CAPTURE,
            GatewayCapability.CAPTURE,
            GatewayCapability.PARTIAL_CAPTURE,
            GatewayCapability.REFUND,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA
    );

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public RazorpayPaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.razorpay.base-url:https://api.razorpay.com}") String baseUrl
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.RAZORPAY;
    }

    @Override
    public ConnectionValidation validate(GatewayConnection connection) {
        validateProfile(connection);
        Credentials credentials = credentials(connection);
        ProviderHttpTransport.Response response = transport.get(
                uri("/v1/payments?count=1"), authorization(credentials)
        );
        if (response.statusCode() == 401) {
            return new ConnectionValidation(false, "RAZORPAY_CREDENTIAL_REJECTED", "Razorpay rejected the configured credential.", Set.of(), "razorpay-rest-v1");
        }
        if (!response.successful()) {
            throw unavailable(response.statusCode());
        }
        return new ConnectionValidation(true, "READY", "Razorpay connection validated.", CAPABILITIES, "razorpay-rest-v1");
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        return switch (request.operationType()) {
            case AUTHORIZE -> createOrder(request, connection);
            case CAPTURE -> capture(request, connection);
            case REFUND -> refund(request, connection);
            case STATUS_QUERY -> queryStatus(new GatewayOperationStatusQuery(
                    null, request.operationId(), request.idempotencyKey(), request.providerReference()), connection);
            case VOID -> throw new GatewayBusinessException(
                    "RAZORPAY_VOID_UNSUPPORTED",
                    "Razorpay automatically releases uncaptured authorizations; explicit void is not available."
            );
        };
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        Credentials credentials = credentials(connection);
        String reference = requireReference(query.providerReference());
        ProviderHttpTransport.Response response;
        JsonNode entity;
        if (reference.startsWith("order_")) {
            response = transport.get(uri("/v1/orders/" + path(reference) + "/payments"), authorization(credentials));
            JsonNode items = requireSuccessful(response).path("items");
            entity = items.isArray() && !items.isEmpty() ? items.get(items.size() - 1) : objectMapper.createObjectNode()
                    .put("id", reference).put("status", "created");
        } else {
            response = transport.get(uri("/v1/payments/" + path(reference)), authorization(credentials));
            entity = requireSuccessful(response);
        }
        return paymentResult(query.gatewayOperationId(), entity, reference);
    }

    @Override
    public GatewayWebhookEvent parseWebhook(GatewayConnection connection, String rawBody, Map<String, String> headers) {
        String secret = credentialResolver.webhookSecret(connection)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> invalidWebhook("RAZORPAY_WEBHOOK_SECRET_MISSING", "Razorpay webhook verification is not configured."));
        String signature = headers.get("x-razorpay-signature");
        if (signature == null || !MessageDigest.isEqual(
                hmac(rawBody, secret).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII)
        )) {
            throw invalidWebhook("RAZORPAY_WEBHOOK_SIGNATURE_INVALID", "Razorpay webhook signature is invalid.");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException exception) {
            throw invalidWebhook("RAZORPAY_WEBHOOK_INVALID", "Razorpay webhook payload is invalid.");
        }
        String eventType = required(root.path("event").asText(), "RAZORPAY_WEBHOOK_TYPE_MISSING");
        JsonNode payment = root.path("payload").path("payment").path("entity");
        JsonNode refund = root.path("payload").path("refund").path("entity");
        boolean refundEvent = eventType.startsWith("refund.");
        JsonNode entity = refundEvent ? refund : payment;
        String entityId = required(entity.path("id").asText(), "RAZORPAY_WEBHOOK_ENTITY_MISSING");
        String eventId = headers.get("x-razorpay-event-id");
        if (eventId == null || eventId.isBlank()) {
            eventId = eventType + ":" + entityId + ":" + entity.path("created_at").asLong(0);
        }
        String providerReference = refundEvent
                ? entity.path("payment_id").asText(null)
                : entity.path("order_id").asText(entityId);
        GatewayWebhookOutcome outcome = switch (eventType) {
            case "payment.authorized" -> GatewayWebhookOutcome.AUTHORIZED;
            case "payment.captured", "order.paid" -> GatewayWebhookOutcome.CAPTURED;
            case "payment.failed" -> GatewayWebhookOutcome.DECLINED;
            case "refund.processed" -> GatewayWebhookOutcome.REFUNDED;
            default -> GatewayWebhookOutcome.PENDING;
        };
        long createdAt = entity.path("created_at").asLong(0);
        return new GatewayWebhookEvent(
                bounded(eventId, 160),
                bounded(eventType, 96),
                providerReference,
                null,
                entityId,
                outcome,
                webhookCode(outcome),
                createdAt > 0 ? Instant.ofEpochSecond(createdAt) : null
        );
    }

    private GatewayOperationResult createOrder(GatewayOperationRequest request, GatewayConnection connection) {
        Credentials credentials = credentials(connection);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency()));
        body.put("currency", request.currency().toUpperCase(Locale.ROOT));
        body.put("receipt", bounded(request.operationId().replaceAll("[^A-Za-z0-9_-]", ""), 40));
        body.put("payment_capture", 0);
        body.put("notes", Map.of(
                "electrahub_payment_intent_id", request.paymentIntentId(),
                "electrahub_operation_id", request.operationId()
        ));
        JsonNode response = requireSuccessful(transport.postJson(
                uri("/v1/orders"), authorization(credentials), json(body)
        ));
        String orderId = requiredProviderValue(response.path("id").asText(), "RAZORPAY_ORDER_INVALID");
        Map<String, String> actionData = new LinkedHashMap<>();
        actionData.put("provider", "RAZORPAY");
        actionData.put("keyId", credentials.keyId());
        actionData.put("orderId", orderId);
        actionData.put("amount", Long.toString(response.path("amount").asLong()));
        actionData.put("currency", response.path("currency").asText(request.currency()));
        if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
            actionData.put("callbackUrl", request.returnUrl().trim());
        }
        return result(GatewayOperationStatus.ACTION_REQUIRED, "PAYMENT_CUSTOMER_ACTION_REQUIRED", orderId, orderId,
                new GatewayAction(GatewayActionType.SDK, null, null, null, actionData));
    }

    private GatewayOperationResult capture(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentId = requirePaymentReference(request.providerReference());
        Map<String, String> form = Map.of(
                "amount", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())),
                "currency", request.currency().toUpperCase(Locale.ROOT)
        );
        JsonNode response = requireSuccessful(transport.postForm(
                uri("/v1/payments/" + path(paymentId) + "/capture"), authorization(credentials(connection)), form
        ));
        return paymentResult(null, response, paymentId);
    }

    private GatewayOperationResult refund(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentId = requirePaymentReference(request.providerReference());
        JsonNode response = requireSuccessful(transport.postForm(
                uri("/v1/payments/" + path(paymentId) + "/refund"),
                authorization(credentials(connection)),
                Map.of("amount", Long.toString(CurrencyMinorUnits.toMinorUnits(request.amount(), request.currency())))
        ));
        String refundId = response.path("id").asText();
        return result(GatewayOperationStatus.PENDING_RECONCILIATION, "PROVIDER_STATUS_PENDING", paymentId, refundId, null);
    }

    private GatewayOperationResult paymentResult(UUID operationId, JsonNode entity, String fallbackReference) {
        String status = entity.path("status").asText("created");
        GatewayOperationStatus gatewayStatus = switch (status) {
            case "authorized", "captured", "refunded" -> GatewayOperationStatus.SUCCEEDED;
            case "failed" -> GatewayOperationStatus.DECLINED;
            default -> GatewayOperationStatus.PENDING_RECONCILIATION;
        };
        String paymentId = entity.path("id").asText(fallbackReference);
        String orderId = entity.path("order_id").asText(fallbackReference);
        return new GatewayOperationResult(
                operationId == null ? UUID.randomUUID() : operationId,
                gatewayStatus,
                gatewayStatus == GatewayOperationStatus.SUCCEEDED ? "APPROVED" : gatewayStatus == GatewayOperationStatus.DECLINED
                        ? "PAYMENT_AUTHORIZATION_DECLINED" : "PROVIDER_STATUS_PENDING",
                orderId,
                paymentId,
                null,
                Instant.now()
        );
    }

    private Credentials credentials(GatewayConnection connection) {
        String configured = credentialResolver.requireCredential(connection);
        try {
            JsonNode value = objectMapper.readTree(configured);
            String keyId = value.path("keyId").asText();
            String keySecret = value.path("keySecret").asText();
            if (keyId.isBlank() || keySecret.isBlank()) {
                throw new GatewayBusinessException("RAZORPAY_CREDENTIAL_INVALID", "Razorpay key ID or secret is missing.");
            }
            boolean test = keyId.startsWith("rzp_test_");
            boolean live = keyId.startsWith("rzp_live_");
            if (connection.environment() == GatewayEnvironment.SANDBOX && !test) {
                throw new GatewayBusinessException("RAZORPAY_TEST_KEY_REQUIRED", "A Razorpay test key is required for a sandbox connection.");
            }
            if (connection.environment() == GatewayEnvironment.PRODUCTION && !live) {
                throw new GatewayBusinessException("RAZORPAY_LIVE_KEY_REQUIRED", "A Razorpay live key is required for a production connection.");
            }
            return new Credentials(keyId, keySecret);
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("RAZORPAY_CREDENTIAL_INVALID", "Razorpay credentials must be a JSON object.");
        }
    }

    private void validateProfile(GatewayConnection connection) {
        String expected = connection.environment() == GatewayEnvironment.SANDBOX ? "razorpay-sandbox" : "razorpay-production";
        if (!expected.equalsIgnoreCase(connection.endpointProfile())) {
            throw new GatewayBusinessException("RAZORPAY_ENDPOINT_PROFILE_INVALID", "Razorpay endpoint profile does not match the environment.");
        }
    }

    private Map<String, String> authorization(Credentials credentials) {
        String encoded = Base64.getEncoder().encodeToString(
                (credentials.keyId() + ":" + credentials.keySecret()).getBytes(StandardCharsets.UTF_8)
        );
        return Map.of("Authorization", "Basic " + encoded, "Accept", "application/json");
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        if (response.successful()) {
            try {
                return objectMapper.readTree(response.body());
            } catch (JsonProcessingException exception) {
                throw new GatewayUnavailableException("RAZORPAY_RESPONSE_INVALID", "Razorpay returned invalid JSON.");
            }
        }
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 404) {
            throw new GatewayBusinessException("RAZORPAY_REQUEST_REJECTED", "Razorpay rejected the payment request.");
        }
        throw unavailable(response.statusCode());
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("RAZORPAY_REQUEST_INVALID", "Unable to create the Razorpay request.");
        }
    }

    private String hmac(String body, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw invalidWebhook("RAZORPAY_WEBHOOK_VERIFICATION_FAILED", "Razorpay webhook verification failed.");
        }
    }

    private String webhookCode(GatewayWebhookOutcome outcome) {
        return switch (outcome) {
            case AUTHORIZED -> "AUTHORIZED";
            case CAPTURED -> "CAPTURED";
            case REFUNDED -> "REFUNDED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            default -> "PROVIDER_STATUS_PENDING";
        };
    }

    private GatewayOperationResult result(GatewayOperationStatus status, String code, String providerReference,
                                          String publicReference, GatewayAction action) {
        return new GatewayOperationResult(UUID.randomUUID(), status, code, providerReference, publicReference, action, Instant.now());
    }

    private String requireReference(String value) {
        if (value == null || (!value.startsWith("pay_") && !value.startsWith("order_"))) {
            throw new GatewayBusinessException("RAZORPAY_REFERENCE_INVALID", "A Razorpay order or payment reference is required.");
        }
        return value;
    }

    private String requirePaymentReference(String value) {
        if (value == null || !value.startsWith("pay_")) {
            throw new GatewayBusinessException("RAZORPAY_PAYMENT_REFERENCE_REQUIRED", "A Razorpay payment reference is required.");
        }
        return value;
    }

    private String requiredProviderValue(String value, String code) {
        if (value == null || value.isBlank()) {
            throw new GatewayUnavailableException(code, "Razorpay returned an invalid response.");
        }
        return value;
    }

    private String required(String value, String code) {
        if (value == null || value.isBlank()) {
            throw invalidWebhook(code, "Razorpay webhook payload is missing required event data.");
        }
        return value;
    }

    private String path(String value) {
        if (!value.matches("^[A-Za-z0-9_]+$")) {
            throw new GatewayBusinessException("RAZORPAY_REFERENCE_INVALID", "Razorpay reference is invalid.");
        }
        return value;
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private GatewayUnavailableException unavailable(int status) {
        return new GatewayUnavailableException("RAZORPAY_UNAVAILABLE_" + status, "Razorpay is temporarily unavailable.");
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

    private record Credentials(String keyId, String keySecret) {
    }
}
