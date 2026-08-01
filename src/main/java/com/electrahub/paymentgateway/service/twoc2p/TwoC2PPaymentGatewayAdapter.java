package com.electrahub.paymentgateway.service.twoc2p;

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

import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class TwoC2PPaymentGatewayAdapter implements PaymentGatewayAdapter {

    static final String DEMO_PROFILE = "2c2p-sandbox-sg-demo";

    private static final Set<GatewayCapability> CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA
    );

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final JwtHs256Codec jwtCodec;
    private final String baseUrl;
    private final String demoMerchantId;
    private final String demoSecretKey;
    private final String webhookBaseUrl;

    public TwoC2PPaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.two-c2p.base-url:https://sandbox-pgw.2c2p.com}") String baseUrl,
            @Value("${app.gateway.providers.two-c2p.demo-merchant-id:JT01}") String demoMerchantId,
            @Value("${app.gateway.providers.two-c2p.demo-secret-key:}") String demoSecretKey,
            @Value("${app.gateway.providers.two-c2p.webhook-base-url:}") String webhookBaseUrl
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.jwtCodec = new JwtHs256Codec(objectMapper);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.demoMerchantId = demoMerchantId == null ? "" : demoMerchantId.trim();
        this.demoSecretKey = demoSecretKey == null ? "" : demoSecretKey.trim();
        this.webhookBaseUrl = stripTrailingSlash(webhookBaseUrl);
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.TWO_C2P;
    }

    @Override
    public boolean requiresCredential(GatewayConnection connection) {
        return !isPublicDemo(connection);
    }

    @Override
    public ConnectionValidation validate(GatewayConnection connection) {
        validateProfile(connection);
        Credentials credentials = credentials(connection);
        if (credentials.merchantId().isBlank() || credentials.secretKey().length() < 32) {
            return new ConnectionValidation(
                    false,
                    "TWO_C2P_CREDENTIAL_INVALID",
                    "2C2P merchant ID or signing key is invalid.",
                    Set.of(),
                    "2c2p-v4.3"
            );
        }
        if (connection.environment() == GatewayEnvironment.SANDBOX) {
            Map<String, Object> probe = new LinkedHashMap<>();
            probe.put("merchantID", credentials.merchantId());
            probe.put("invoiceNo", validationInvoice(connection.id()));
            probe.put("locale", "en");
            JsonNode response;
            try {
                response = postSigned("/payment/4.3/paymentInquiry", probe, credentials.secretKey(), true);
            } catch (GatewayBusinessException exception) {
                return new ConnectionValidation(
                        false,
                        "TWO_C2P_CREDENTIAL_REJECTED",
                        "2C2P did not return a response signed with the configured sandbox key.",
                        Set.of(),
                        "2c2p-v4.3"
                );
            }
            String responseMerchantId = response.path("merchantID").asText();
            if (response.path("respCode").asText().isBlank()
                    || (!responseMerchantId.isBlank() && !credentials.merchantId().equals(responseMerchantId))) {
                return new ConnectionValidation(
                        false,
                        "TWO_C2P_CREDENTIAL_REJECTED",
                        "2C2P rejected the configured sandbox merchant or signing key.",
                        Set.of(),
                        "2c2p-v4.3"
                );
            }
        }
        return new ConnectionValidation(true, "READY", "2C2P sandbox configuration validated.", CAPABILITIES, "2c2p-v4.3");
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        if (request.operationType() != com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType.AUTHORIZE) {
            throw new GatewayBusinessException(
                    "TWO_C2P_DEMO_OPERATION_UNSUPPORTED",
                    "The free 2C2P demo supports hosted payment and inquiry; maintenance exchange keys are required for this operation."
            );
        }
        if (isPublicDemo(connection) && !"SGD".equalsIgnoreCase(request.currency())) {
            throw new GatewayBusinessException("TWO_C2P_DEMO_SGD_REQUIRED", "The public Singapore demo accepts SGD only.");
        }

        Credentials credentials = credentials(connection);
        String invoiceNo = invoiceNo(request.operationId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantID", credentials.merchantId());
        payload.put("invoiceNo", invoiceNo);
        payload.put("idempotencyID", request.idempotencyKey());
        payload.put("description", "ElectraHub charging payment " + request.paymentIntentId());
        payload.put("amount", request.amount().setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        payload.put("currencyCode", request.currency().toUpperCase(Locale.ROOT));
        payload.put("request3DS", "Y");
        payload.put("locale", "en");
        if (connection.environment() == GatewayEnvironment.SANDBOX && !isPublicDemo(connection)) {
            payload.put("paymentChannel", java.util.List.of("CC"));
            payload.put("transactionMode", "PREAUTH");
        }
        if (request.returnUrl() != null && !request.returnUrl().isBlank()) {
            String returnUrl = request.returnUrl().trim();
            payload.put("frontendReturnUrl", returnUrl);
            if (!isPublicDemo(connection) && returnUrl.toLowerCase(Locale.ROOT).startsWith("electrahub://")) {
                payload.put("schemeReturnUrl", returnUrl);
                payload.put("appBundleID", "net.electrahub.driverportalios");
            }
        }
        if (!webhookBaseUrl.isBlank()) {
            payload.put("backendReturnUrl", webhookBaseUrl + "/" + connection.id());
        }

        JsonNode response = postSigned("/payment/4.3/paymentToken", payload, credentials.secretKey());
        String responseCode = response.path("respCode").asText();
        if (!"0000".equals(responseCode)) {
            throw new GatewayBusinessException(
                    "TWO_C2P_" + safeCode(responseCode),
                    "2C2P rejected the hosted payment request."
            );
        }
        String paymentToken = response.path("paymentToken").asText();
        String paymentUrl = response.path("webPaymentUrl").asText();
        if (paymentToken.isBlank() || paymentUrl.isBlank()) {
            throw new GatewayUnavailableException("TWO_C2P_RESPONSE_INVALID", "2C2P did not return a hosted payment action.");
        }
        return new GatewayOperationResult(
                UUID.randomUUID(),
                GatewayOperationStatus.ACTION_REQUIRED,
                "PAYMENT_CUSTOMER_ACTION_REQUIRED",
                paymentToken,
                invoiceNo,
                new GatewayAction(
                        GatewayActionType.REDIRECT,
                        paymentUrl,
                        null,
                        Instant.now().plus(30, ChronoUnit.MINUTES),
                        Map.of("provider", "TWO_C2P", "invoiceNo", invoiceNo)
                ),
                Instant.now()
        );
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        validateProfile(connection);
        Credentials credentials = credentials(connection);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantID", credentials.merchantId());
        if (query.providerReference() != null && !query.providerReference().isBlank()) {
            payload.put("paymentToken", query.providerReference());
        } else {
            payload.put("invoiceNo", invoiceNo(query.operationId()));
        }
        payload.put("locale", "en");

        JsonNode response = postSigned("/payment/4.3/paymentInquiry", payload, credentials.secretKey());
        String responseCode = response.path("respCode").asText();
        GatewayOperationStatus status = "0000".equals(responseCode)
                ? GatewayOperationStatus.SUCCEEDED
                : GatewayOperationStatus.PENDING_RECONCILIATION;
        String publicReference = response.path("referenceNo").asText();
        if (publicReference.isBlank()) {
            publicReference = response.path("invoiceNo").asText(null);
        }
        return new GatewayOperationResult(
                query.gatewayOperationId(),
                status,
                status == GatewayOperationStatus.SUCCEEDED ? "APPROVED" : "TWO_C2P_STATUS_PENDING",
                query.providerReference(),
                publicReference,
                null,
                Instant.now()
        );
    }

    @Override
    public GatewayWebhookEvent parseWebhook(
            GatewayConnection connection,
            String rawBody,
            Map<String, String> headers
    ) {
        validateProfile(connection);
        Credentials credentials = credentials(connection);
        JsonNode outer;
        try {
            outer = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_INVALID", "2C2P webhook payload is invalid.");
        }
        String token = outer.path("payload").asText();
        if (token.isBlank()) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_PAYLOAD_MISSING", "2C2P signed webhook payload is missing.");
        }

        JsonNode payload;
        try {
            payload = jwtCodec.decodeAndVerify(token, credentials.secretKey());
        } catch (GatewayBusinessException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_SIGNATURE_INVALID", "2C2P webhook signature is invalid.");
        }
        String merchantId = requiredWebhookValue(payload.path("merchantID").asText(), "TWO_C2P_WEBHOOK_MERCHANT_MISSING");
        if (!merchantId.equals(credentials.merchantId())) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_MERCHANT_INVALID", "2C2P webhook merchant does not match the connection.");
        }
        String invoiceNo = requiredWebhookValue(payload.path("invoiceNo").asText(), "TWO_C2P_WEBHOOK_INVOICE_MISSING");
        String responseCode = requiredWebhookValue(payload.path("respCode").asText(), "TWO_C2P_WEBHOOK_RESPONSE_MISSING");
        String transactionReference = payload.path("tranRef").asText(null);
        String publicReference = payload.path("referenceNo").asText(null);
        String transactionDate = payload.path("transactionDateTime").asText(null);
        String providerEventId = boundedEventId(String.join(":",
                merchantId,
                invoiceNo,
                transactionReference == null ? "none" : transactionReference,
                responseCode
        ));
        GatewayWebhookOutcome outcome = "0000".equals(responseCode)
                ? GatewayWebhookOutcome.CAPTURED
                : GatewayWebhookOutcome.DECLINED;
        return new GatewayWebhookEvent(
                providerEventId,
                "payment.response.backend",
                transactionReference,
                invoiceNo,
                publicReference,
                outcome,
                "0000".equals(responseCode) ? "CAPTURED" : "TWO_C2P_" + safeCode(responseCode),
                parseTransactionTime(transactionDate)
        );
    }

    private JsonNode postSigned(String path, Map<String, Object> payload, String secretKey) {
        return postSigned(path, payload, secretKey, false);
    }

    private JsonNode postSigned(
            String path,
            Map<String, Object> payload,
            String secretKey,
            boolean requireSignedResponse
    ) {
        String token = jwtCodec.encode(payload, secretKey);
        String requestBody;
        try {
            requestBody = objectMapper.writeValueAsString(Map.of("payload", token));
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("TWO_C2P_REQUEST_INVALID", "Unable to create the 2C2P request.");
        }
        ProviderHttpTransport.Response httpResponse = transport.postJson(
                URI.create(baseUrl + path),
                Map.of("Accept", "application/json"),
                requestBody
        );
        if (!httpResponse.successful()) {
            throw new GatewayUnavailableException(
                    "TWO_C2P_UNAVAILABLE_" + httpResponse.statusCode(),
                    "2C2P is temporarily unavailable."
            );
        }
        JsonNode outer;
        try {
            outer = objectMapper.readTree(httpResponse.body());
        } catch (JsonProcessingException exception) {
            throw new GatewayUnavailableException("TWO_C2P_RESPONSE_INVALID", "2C2P returned invalid JSON.");
        }
        String responseToken = outer.path("payload").asText();
        if (requireSignedResponse && responseToken.isBlank()) {
            throw new GatewayBusinessException(
                    "TWO_C2P_RESPONSE_UNSIGNED",
                    "2C2P did not return the required signed response."
            );
        }
        return responseToken.isBlank() ? outer : jwtCodec.decodeAndVerify(responseToken, secretKey);
    }

    private Credentials credentials(GatewayConnection connection) {
        if (isPublicDemo(connection)) {
            if (demoSecretKey.isBlank()) {
                throw new GatewayBusinessException(
                        "TWO_C2P_DEMO_KEY_UNAVAILABLE",
                        "Configure the published 2C2P Singapore demo key in the runtime secret."
                );
            }
            return new Credentials(demoMerchantId, demoSecretKey);
        }
        String configured = credentialResolver.requireCredential(connection);
        try {
            JsonNode json = objectMapper.readTree(configured);
            return new Credentials(json.path("merchantId").asText(), json.path("secretKey").asText());
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("TWO_C2P_CREDENTIAL_INVALID", "2C2P credentials must be a JSON object.");
        }
    }

    private boolean isPublicDemo(GatewayConnection connection) {
        return connection.environment() == GatewayEnvironment.SANDBOX
                && DEMO_PROFILE.equalsIgnoreCase(connection.endpointProfile());
    }

    private void validateProfile(GatewayConnection connection) {
        if (connection.environment() == GatewayEnvironment.SANDBOX && isPublicDemo(connection)) {
            return;
        }
        String expected = connection.environment() == GatewayEnvironment.SANDBOX ? "2c2p-sandbox" : "2c2p-production";
        if (!expected.equalsIgnoreCase(connection.endpointProfile())) {
            throw new GatewayBusinessException("TWO_C2P_ENDPOINT_PROFILE_INVALID", "2C2P endpoint profile does not match the environment.");
        }
    }

    private String invoiceNo(String value) {
        String normalized = value == null ? "" : value.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.isBlank()) {
            normalized = UUID.randomUUID().toString().replace("-", "");
        }
        return normalized.length() <= 50 ? normalized : normalized.substring(0, 50);
    }

    private String validationInvoice(UUID connectionId) {
        String suffix = connectionId == null
                ? UUID.randomUUID().toString().replace("-", "")
                : connectionId.toString().replace("-", "");
        return "EHVALIDATE" + suffix.substring(0, Math.min(32, suffix.length()));
    }

    private String safeCode(String value) {
        String code = value == null || value.isBlank() ? "REQUEST_FAILED" : value.replaceAll("[^A-Za-z0-9]", "_");
        return code.toUpperCase(Locale.ROOT);
    }

    private Instant parseTransactionTime(String value) {
        if (value == null || !value.matches("^\\d{14}$")) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                    .atZone(ZoneId.of("Asia/Singapore"))
                    .toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private String requiredWebhookValue(String value, String code) {
        if (value == null || value.isBlank()) {
            throw invalidWebhook(code, "2C2P webhook payload is missing required event data.");
        }
        return value;
    }

    private String boundedEventId(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160);
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

    private record Credentials(String merchantId, String secretKey) {
    }
}
