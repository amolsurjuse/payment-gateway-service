package com.electrahub.paymentgateway.service.mollie;

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

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class MolliePaymentGatewayAdapter implements PaymentGatewayAdapter {

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
    private final String webhookBaseUrl;

    public MolliePaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.mollie.base-url:https://api.mollie.com}") String baseUrl,
            @Value("${app.gateway.providers.mollie.webhook-base-url:}") String webhookBaseUrl
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.webhookBaseUrl = stripTrailingSlash(webhookBaseUrl);
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.MOLLIE;
    }

    @Override
    public ConnectionValidation validate(GatewayConnection connection) {
        validateProfile(connection);
        ProviderHttpTransport.Response response = transport.get(
                uri("/v2/payments?limit=1"), authorization(apiKey(connection))
        );
        if (response.statusCode() == 401) {
            return new ConnectionValidation(false, "MOLLIE_CREDENTIAL_REJECTED", "Mollie rejected the configured credential.", Set.of(), "mollie-rest-v2");
        }
        if (!response.successful()) {
            throw unavailable(response.statusCode());
        }
        return new ConnectionValidation(true, "READY", "Mollie connection validated.", CAPABILITIES, "mollie-rest-v2");
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        return switch (request.operationType()) {
            case AUTHORIZE -> authorize(request, connection);
            case CAPTURE -> capture(request, connection);
            case VOID -> cancel(request, connection);
            case REFUND -> refund(request, connection);
            case STATUS_QUERY -> queryStatus(new GatewayOperationStatusQuery(
                    null, request.operationId(), request.idempotencyKey(), request.providerReference()), connection);
        };
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        String paymentId = paymentId(query.providerReference());
        JsonNode payment = requireSuccessful(transport.get(
                uri("/v2/payments/" + path(paymentId)), authorization(apiKey(connection))
        ));
        return paymentResult(query.gatewayOperationId(), payment);
    }

    /** Mollie classic callbacks are authenticated by fetching the resource with the account API key. */
    @Override
    public GatewayWebhookEvent parseWebhook(GatewayConnection connection, String rawBody, Map<String, String> headers) {
        String paymentId = parseWebhookId(rawBody);
        JsonNode payment = requireSuccessful(transport.get(
                uri("/v2/payments/" + path(paymentId)), authorization(apiKey(connection))
        ));
        if (!paymentId.equals(payment.path("id").asText())) {
            throw invalidWebhook("MOLLIE_WEBHOOK_RESOURCE_INVALID", "Mollie webhook resource could not be verified.");
        }
        String status = payment.path("status").asText("open");
        GatewayWebhookOutcome outcome = switch (status) {
            case "authorized" -> GatewayWebhookOutcome.AUTHORIZED;
            case "paid" -> GatewayWebhookOutcome.CAPTURED;
            case "failed" -> GatewayWebhookOutcome.DECLINED;
            case "canceled", "expired" -> GatewayWebhookOutcome.VOIDED;
            default -> GatewayWebhookOutcome.PENDING;
        };
        String statusAt = statusTimestamp(payment, status);
        String eventId = "mollie:" + paymentId + ":" + status + ":" + (statusAt == null ? "none" : statusAt);
        String merchantReference = payment.path("metadata").path("electrahub_payment_intent_id").asText(null);
        return new GatewayWebhookEvent(
                bounded(eventId, 160),
                bounded("payment." + status, 96),
                paymentId,
                merchantReference,
                paymentId,
                outcome,
                webhookCode(outcome),
                parseInstant(statusAt)
        );
    }

    private GatewayOperationResult authorize(GatewayOperationRequest request, GatewayConnection connection) {
        if (request.returnUrl() == null || request.returnUrl().isBlank()) {
            throw new GatewayBusinessException("MOLLIE_RETURN_URL_REQUIRED", "Mollie hosted checkout requires a return URL.");
        }
        if (webhookBaseUrl.isBlank()) {
            throw new GatewayBusinessException("MOLLIE_WEBHOOK_URL_REQUIRED", "Mollie webhook URL is not configured.");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("amount", amount(request));
        body.put("description", bounded("ElectraHub charging " + request.paymentIntentId(), 255));
        body.put("redirectUrl", request.returnUrl().trim());
        body.put("webhookUrl", webhookBaseUrl + "/" + connection.id());
        body.put("method", "creditcard");
        body.put("captureMode", "manual");
        body.put("metadata", Map.of(
                "electrahub_payment_intent_id", request.paymentIntentId(),
                "electrahub_operation_id", request.operationId()
        ));
        JsonNode payment = requireSuccessful(transport.postJson(
                uri("/v2/payments"), authorization(apiKey(connection)), json(body)
        ));
        String checkoutUrl = payment.path("_links").path("checkout").path("href").asText(null);
        String paymentId = payment.path("id").asText();
        if (paymentId.isBlank() || checkoutUrl == null || checkoutUrl.isBlank()) {
            throw new GatewayUnavailableException("MOLLIE_CHECKOUT_INVALID", "Mollie did not return a hosted checkout action.");
        }
        return new GatewayOperationResult(
                UUID.randomUUID(),
                GatewayOperationStatus.ACTION_REQUIRED,
                "PAYMENT_CUSTOMER_ACTION_REQUIRED",
                paymentId,
                paymentId,
                new GatewayAction(GatewayActionType.REDIRECT, checkoutUrl, null, null, Map.of("provider", "MOLLIE")),
                Instant.now()
        );
    }

    private GatewayOperationResult capture(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentId = paymentId(request.providerReference());
        JsonNode payment = requireSuccessful(transport.get(
                uri("/v2/payments/" + path(paymentId)), authorization(apiKey(connection))
        ));
        if ("paid".equals(payment.path("status").asText())) {
            return result(GatewayOperationStatus.SUCCEEDED, paymentId, paymentId);
        }
        Map<String, Object> body = Map.of(
                "amount", amount(request),
                "description", bounded("ElectraHub capture " + request.operationId(), 255)
        );
        JsonNode capture = requireSuccessful(transport.postJson(
                uri("/v2/payments/" + path(paymentId) + "/captures"), authorization(apiKey(connection)), json(body)
        ));
        String status = capture.path("status").asText("pending");
        GatewayOperationStatus gatewayStatus = "succeeded".equals(status)
                ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.PENDING_RECONCILIATION;
        return result(gatewayStatus, paymentId, capture.path("id").asText(paymentId));
    }

    private GatewayOperationResult cancel(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentId = paymentId(request.providerReference());
        ProviderHttpTransport.Response response = transport.delete(
                uri("/v2/payments/" + path(paymentId)), authorization(apiKey(connection))
        );
        if (!response.successful()) {
            requireSuccessful(response);
        }
        return result(GatewayOperationStatus.SUCCEEDED, paymentId, paymentId);
    }

    private GatewayOperationResult refund(GatewayOperationRequest request, GatewayConnection connection) {
        String paymentId = paymentId(request.providerReference());
        JsonNode refund = requireSuccessful(transport.postJson(
                uri("/v2/payments/" + path(paymentId) + "/refunds"),
                authorization(apiKey(connection)),
                json(Map.of("amount", amount(request), "description", bounded("ElectraHub refund " + request.operationId(), 255)))
        ));
        String status = refund.path("status").asText("queued");
        GatewayOperationStatus gatewayStatus = "refunded".equals(status)
                ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.PENDING_RECONCILIATION;
        return result(gatewayStatus, paymentId, refund.path("id").asText(paymentId));
    }

    private GatewayOperationResult paymentResult(UUID operationId, JsonNode payment) {
        String status = payment.path("status").asText("open");
        GatewayOperationStatus gatewayStatus = switch (status) {
            case "authorized", "paid" -> GatewayOperationStatus.SUCCEEDED;
            case "failed", "canceled", "expired" -> GatewayOperationStatus.DECLINED;
            default -> GatewayOperationStatus.PENDING_RECONCILIATION;
        };
        String id = payment.path("id").asText();
        return new GatewayOperationResult(
                operationId == null ? UUID.randomUUID() : operationId,
                gatewayStatus,
                code(gatewayStatus),
                id,
                id,
                null,
                Instant.now()
        );
    }

    private GatewayOperationResult result(GatewayOperationStatus status, String providerReference, String publicReference) {
        return new GatewayOperationResult(UUID.randomUUID(), status, code(status), providerReference, publicReference, null, Instant.now());
    }

    private Map<String, String> amount(GatewayOperationRequest request) {
        return Map.of(
                "currency", request.currency().toUpperCase(Locale.ROOT),
                "value", request.amount().setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()
        );
    }

    private String apiKey(GatewayConnection connection) {
        String configured = credentialResolver.requireCredential(connection);
        String key = configured;
        if (configured.startsWith("{")) {
            try {
                key = objectMapper.readTree(configured).path("apiKey").asText();
            } catch (JsonProcessingException exception) {
                throw new GatewayBusinessException("MOLLIE_CREDENTIAL_INVALID", "Mollie credentials must be a valid JSON object or API key.");
            }
        }
        boolean test = key != null && key.startsWith("test_");
        boolean live = key != null && key.startsWith("live_");
        if (connection.environment() == GatewayEnvironment.SANDBOX && !test) {
            throw new GatewayBusinessException("MOLLIE_TEST_KEY_REQUIRED", "A Mollie test API key is required for a sandbox connection.");
        }
        if (connection.environment() == GatewayEnvironment.PRODUCTION && !live) {
            throw new GatewayBusinessException("MOLLIE_LIVE_KEY_REQUIRED", "A Mollie live API key is required for a production connection.");
        }
        return key;
    }

    private void validateProfile(GatewayConnection connection) {
        String expected = connection.environment() == GatewayEnvironment.SANDBOX ? "mollie-sandbox" : "mollie-production";
        if (!expected.equalsIgnoreCase(connection.endpointProfile())) {
            throw new GatewayBusinessException("MOLLIE_ENDPOINT_PROFILE_INVALID", "Mollie endpoint profile does not match the environment.");
        }
    }

    private Map<String, String> authorization(String apiKey) {
        return Map.of("Authorization", "Bearer " + apiKey, "Accept", "application/json");
    }

    private JsonNode requireSuccessful(ProviderHttpTransport.Response response) {
        if (response.successful()) {
            if (response.body() == null || response.body().isBlank()) {
                return objectMapper.createObjectNode();
            }
            try {
                return objectMapper.readTree(response.body());
            } catch (JsonProcessingException exception) {
                throw new GatewayUnavailableException("MOLLIE_RESPONSE_INVALID", "Mollie returned invalid JSON.");
            }
        }
        if (response.statusCode() == 400 || response.statusCode() == 401 || response.statusCode() == 404 || response.statusCode() == 422) {
            throw new GatewayBusinessException("MOLLIE_REQUEST_REJECTED", "Mollie rejected the payment request.");
        }
        throw unavailable(response.statusCode());
    }

    private String parseWebhookId(String rawBody) {
        if (rawBody == null) {
            throw invalidWebhook("MOLLIE_WEBHOOK_INVALID", "Mollie webhook resource ID is missing.");
        }
        for (String pair : rawBody.split("&")) {
            String[] values = pair.split("=", 2);
            if (values.length == 2 && "id".equals(URLDecoder.decode(values[0], StandardCharsets.UTF_8))) {
                String id = URLDecoder.decode(values[1], StandardCharsets.UTF_8);
                if (id.matches("^tr_[A-Za-z0-9]+$")) {
                    return id;
                }
            }
        }
        throw invalidWebhook("MOLLIE_WEBHOOK_INVALID", "Mollie webhook resource ID is invalid.");
    }

    private String statusTimestamp(JsonNode payment, String status) {
        String field = switch (status) {
            case "authorized" -> "authorizedAt";
            case "paid" -> "paidAt";
            case "failed" -> "failedAt";
            case "canceled" -> "canceledAt";
            case "expired" -> "expiredAt";
            default -> "createdAt";
        };
        return payment.path(field).asText(null);
    }

    private Instant parseInstant(String value) {
        try {
            return value == null ? null : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String webhookCode(GatewayWebhookOutcome outcome) {
        return switch (outcome) {
            case AUTHORIZED -> "AUTHORIZED";
            case CAPTURED -> "CAPTURED";
            case VOIDED -> "VOIDED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            default -> "PROVIDER_STATUS_PENDING";
        };
    }

    private String code(GatewayOperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> "APPROVED";
            case DECLINED -> "PAYMENT_AUTHORIZATION_DECLINED";
            case ACTION_REQUIRED -> "PAYMENT_CUSTOMER_ACTION_REQUIRED";
            case PENDING_RECONCILIATION -> "PROVIDER_STATUS_PENDING";
            case FAILED -> "PAYMENT_PROVIDER_FAILED";
        };
    }

    private String paymentId(String value) {
        if (value == null || !value.matches("^tr_[A-Za-z0-9]+$")) {
            throw new GatewayBusinessException("MOLLIE_PAYMENT_REFERENCE_REQUIRED", "A Mollie payment reference is required.");
        }
        return value;
    }

    private String path(String value) {
        if (!value.matches("^[A-Za-z0-9_]+$")) {
            throw new GatewayBusinessException("MOLLIE_REFERENCE_INVALID", "Mollie reference is invalid.");
        }
        return value;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("MOLLIE_REQUEST_INVALID", "Unable to create the Mollie request.");
        }
    }

    private String bounded(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private GatewayUnavailableException unavailable(int status) {
        return new GatewayUnavailableException("MOLLIE_UNAVAILABLE_" + status, "Mollie is temporarily unavailable.");
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
}
