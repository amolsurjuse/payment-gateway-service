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
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class TwoC2PPaymentGatewayAdapter implements PaymentGatewayAdapter {

    static final String DEMO_PROFILE = "2c2p-sandbox-sg-demo";
    static final String PRIVATE_SANDBOX_PROFILE = "2c2p-sandbox";
    static final String MAINTENANCE_PATH = "/2C2PFrontend/PaymentAction/2.0/action";

    private static final Set<GatewayCapability> DEMO_CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA
    );
    private static final Set<GatewayCapability> PRIVATE_SANDBOX_CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.MANUAL_CAPTURE,
            GatewayCapability.CAPTURE,
            GatewayCapability.VOID,
            GatewayCapability.REFUND,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA
    );
    private static final Set<String> PAYMENT_PENDING_CODES = Set.of("0001", "2001", "4009");
    private static final Set<String> DEMO_PROBE_ACCEPTABLE_CODES = Set.of("0000", "2001", "2002");
    private static final Set<String> CAPTURE_READY_VALIDATION_STATUSES = Set.of("A", "RS");
    private static final Set<String> PAYMENT_DECLINED_CODES = Set.of(
            "0003", "0004", "2003", "4004", "4005", "4012", "4013", "4014", "4015",
            "4033", "4034", "4035", "4036", "4041", "4043", "4051", "4054", "4055",
            "4057", "4059", "4061", "4062", "4063", "4065", "4067", "4075", "4080",
            "4081", "5007", "5009", "5014", "5017", "5019", "9020", "9035"
    );
    private static final Set<String> PAYMENT_TRANSIENT_CODES = Set.of(
            "0999", "2002", "4020", "4021", "4022", "4050", "4068", "4090", "4091",
            "4093", "4094", "4095", "4096", "4099", "4203", "5002", "5998",
            "9990", "9991", "9992", "9993", "9994", "9995", "9996", "9997", "9998", "9999"
    );
    private static final Set<String> UNCERTAIN_MAINTENANCE_CODES = Set.of(
            "29", "34", "39", "42", "49", "50", "51", "95"
    );

    private final ProviderHttpTransport transport;
    private final ProviderCredentialResolver credentialResolver;
    private final ObjectMapper objectMapper;
    private final JwtHs256Codec jwtCodec;
    private final TwoC2PMaintenanceCodec maintenanceCodec;
    private final String baseUrl;
    private final String maintenanceBaseUrl;
    private final String demoMerchantId;
    private final String demoSecretKey;
    private final String webhookBaseUrl;

    @Autowired
    public TwoC2PPaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            @Value("${app.gateway.providers.two-c2p.base-url:https://sandbox-pgw.2c2p.com}") String baseUrl,
            @Value("${app.gateway.providers.two-c2p.maintenance-base-url:https://demo2.2c2p.com}") String maintenanceBaseUrl,
            @Value("${app.gateway.providers.two-c2p.demo-merchant-id:JT01}") String demoMerchantId,
            @Value("${app.gateway.providers.two-c2p.demo-secret-key:}") String demoSecretKey,
            @Value("${app.gateway.providers.two-c2p.webhook-base-url:}") String webhookBaseUrl
    ) {
        this.transport = transport;
        this.credentialResolver = credentialResolver;
        this.objectMapper = objectMapper;
        this.jwtCodec = new JwtHs256Codec(objectMapper);
        this.maintenanceCodec = new TwoC2PMaintenanceCodec(objectMapper);
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.maintenanceBaseUrl = stripTrailingSlash(maintenanceBaseUrl);
        this.demoMerchantId = demoMerchantId == null ? "" : demoMerchantId.trim();
        this.demoSecretKey = demoSecretKey == null ? "" : demoSecretKey.trim();
        this.webhookBaseUrl = stripTrailingSlash(webhookBaseUrl);
    }

    /** Backward-compatible constructor used by isolated adapter tests. */
    public TwoC2PPaymentGatewayAdapter(
            ProviderHttpTransport transport,
            ProviderCredentialResolver credentialResolver,
            ObjectMapper objectMapper,
            String baseUrl,
            String demoMerchantId,
            String demoSecretKey,
            String webhookBaseUrl
    ) {
        this(transport, credentialResolver, objectMapper, baseUrl, baseUrl,
                demoMerchantId, demoSecretKey, webhookBaseUrl);
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
        Credentials credentials;
        try {
            credentials = credentials(connection);
        } catch (GatewayBusinessException exception) {
            return invalidValidation(exception.code(), "2C2P credential material is invalid.");
        }
        if (!credentials.valid()) {
            return invalidValidation("TWO_C2P_CREDENTIAL_INVALID", "2C2P merchant ID or signing key is invalid.");
        }

        if (isPublicDemo(connection)) {
            String validationInvoice = validationInvoice(connection.id());
            try {
                JsonNode inquiry = paymentInquiry(validationInvoice, credentials);
                validatePaymentIdentity(inquiry, credentials.merchantId(), validationInvoice, null, null, true);
                if (!DEMO_PROBE_ACCEPTABLE_CODES.contains(inquiry.path("respCode").asText())) {
                    return invalidValidation("TWO_C2P_CREDENTIAL_REJECTED", "2C2P rejected the sandbox credential.");
                }
            } catch (GatewayBusinessException exception) {
                return invalidValidation(
                        "TWO_C2P_CREDENTIAL_REJECTED",
                        "2C2P did not return a response signed with the configured sandbox key."
                );
            }
            return new ConnectionValidation(
                    true,
                    "READY",
                    "2C2P public demo validated; maintenance capabilities remain disabled.",
                    DEMO_CAPABILITIES,
                    "2c2p-v4.3"
            );
        }

        if (isPrivateSandbox(connection)) {
            ValidationProbe probe;
            try {
                probe = validationProbe(credentials);
            } catch (GatewayBusinessException exception) {
                return invalidValidation(exception.code(), exception.getMessage());
            }

            JsonNode inquiry;
            try {
                inquiry = paymentInquiry(probe.invoiceNo(), credentials);
            } catch (GatewayBusinessException exception) {
                return invalidValidation(
                        "TWO_C2P_VALIDATION_PROBE_REJECTED",
                        "2C2P did not return a signed Payment Inquiry response for the configured validation transaction."
                );
            }
            try {
                validatePaymentIdentity(inquiry, credentials.merchantId(), probe.invoiceNo(),
                        probe.amount(), probe.currency(), true);
            } catch (GatewayBusinessException exception) {
                return invalidValidation(
                        "TWO_C2P_VALIDATION_PROBE_MISMATCH",
                        "2C2P Payment Inquiry returned data that does not match the configured validation transaction."
                );
            }
            if (!"0000".equals(inquiry.path("respCode").asText())) {
                return invalidValidation(
                        "TWO_C2P_VALIDATION_PROBE_REJECTED",
                        "The configured 2C2P validation transaction is not a successful sandbox PREAUTH payment."
                );
            }

            TwoC2PMaintenanceCodec.MaintenanceResponse response;
            try {
                TwoC2PMaintenanceCodec.KeyMaterial keys = maintenanceKeys(connection);
                response = maintenanceExchange(
                        new TwoC2PMaintenanceCodec.MaintenanceRequest(
                                credentials.merchantId(), "I", probe.invoiceNo(),
                                probe.amount(), null, null
                        ),
                        keys
                );
            } catch (GatewayBusinessException exception) {
                return invalidValidation(
                        "TWO_C2P_MAINTENANCE_KEY_REJECTED",
                        "2C2P did not complete a signed and encrypted sandbox maintenance exchange."
                );
            }
            try {
                validateMaintenanceIdentity(response, credentials.merchantId(), "I",
                        probe.invoiceNo(), probe.amount(), null);
                if (!credentials.merchantId().equals(response.merchantId())) {
                    throw new GatewayBusinessException(
                            "TWO_C2P_MAINTENANCE_IDENTITY_MISMATCH",
                            "2C2P omitted the merchant identity from the validation response."
                    );
                }
            } catch (GatewayBusinessException exception) {
                return invalidValidation(
                        "TWO_C2P_VALIDATION_PROBE_MISMATCH",
                        "2C2P Payment Action inquiry returned data that does not match the configured validation transaction."
                );
            }
            if (!"00".equals(response.responseCode())) {
                return invalidValidation(
                        "TWO_C2P_MAINTENANCE_PROBE_REJECTED",
                        "2C2P rejected the protected inquiry for the configured validation transaction."
                );
            }
            if (!CAPTURE_READY_VALIDATION_STATUSES.contains(upper(response.status()))) {
                return invalidValidation(
                        "TWO_C2P_VALIDATION_PROBE_NOT_CAPTURE_READY",
                        "The configured 2C2P validation transaction is not a capture-ready PREAUTH payment."
                );
            }
            return new ConnectionValidation(
                    true,
                    "READY",
                    "2C2P private sandbox payment and maintenance credentials validated against a capture-ready PREAUTH transaction.",
                    PRIVATE_SANDBOX_CAPABILITIES,
                    "2c2p-v4.3"
            );
        }

        return new ConnectionValidation(
                true,
                "READY",
                "2C2P payment credentials validated; maintenance capabilities remain disabled.",
                DEMO_CAPABILITIES,
                "2c2p-v4.3"
        );
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        validateProfile(connection);
        return switch (request.operationType()) {
            case AUTHORIZE -> authorize(request, connection);
            case CAPTURE -> maintenanceMutation(request, connection, "S");
            case VOID -> maintenanceMutation(request, connection, "V");
            case REFUND -> maintenanceMutation(request, connection, "R");
            case STATUS_QUERY -> throw new GatewayBusinessException(
                    "TWO_C2P_OPERATION_UNSUPPORTED",
                    "Use the provider status inquiry operation for 2C2P reconciliation."
            );
        };
    }

    private GatewayOperationResult authorize(GatewayOperationRequest request, GatewayConnection connection) {
        if (isPublicDemo(connection) && !"SGD".equalsIgnoreCase(request.currency())) {
            throw new GatewayBusinessException("TWO_C2P_DEMO_SGD_REQUIRED", "The public Singapore demo accepts SGD only.");
        }
        Credentials credentials = credentials(connection);
        String invoiceNo = invoiceNo(request.operationId());
        BigDecimal amount = amount(request.amount());
        String currency = currency(request.currency());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantID", credentials.merchantId());
        payload.put("invoiceNo", invoiceNo);
        payload.put("idempotencyID", idempotencyId(request.idempotencyKey()));
        payload.put("description", "ElectraHub charging payment " + request.paymentIntentId());
        payload.put("amount", amount.toPlainString());
        payload.put("currencyCode", currency);
        payload.put("request3DS", "Y");
        payload.put("locale", "en");
        if (isPrivateSandbox(connection)) {
            payload.put("paymentChannel", List.of("CC"));
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
        String responseCode = required(response, "respCode", "TWO_C2P_RESPONSE_INVALID");
        if (!"0000".equals(responseCode)) {
            throw new GatewayBusinessException(
                    "TWO_C2P_" + safeCode(responseCode),
                    "2C2P rejected the hosted payment request."
            );
        }
        rejectMismatchedOptional(response, "merchantID", credentials.merchantId());
        rejectMismatchedOptional(response, "invoiceNo", invoiceNo);
        String paymentToken = response.path("paymentToken").asText();
        String paymentUrl = response.path("webPaymentUrl").asText();
        if (paymentToken.isBlank() || paymentUrl.isBlank()) {
            throw new GatewayUnavailableException("TWO_C2P_RESPONSE_INVALID", "2C2P did not return a hosted payment action.");
        }
        return new GatewayOperationResult(
                UUID.randomUUID(),
                GatewayOperationStatus.ACTION_REQUIRED,
                "PAYMENT_CUSTOMER_ACTION_REQUIRED",
                invoiceNo,
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

    private GatewayOperationResult maintenanceMutation(
            GatewayOperationRequest request,
            GatewayConnection connection,
            String processType
    ) {
        requirePrivateSandboxMaintenance(connection);
        Credentials credentials = credentials(connection);
        String invoiceNo = stableInvoice(request.providerReference());
        BigDecimal amount = amount(request.amount());
        String idempotencyId = idempotencyId(request.idempotencyKey());
        String notifyUrl = "R".equals(processType) && !webhookBaseUrl.isBlank()
                ? webhookBaseUrl + "/" + connection.id()
                : null;
        TwoC2PMaintenanceCodec.MaintenanceResponse response = maintenanceExchange(
                new TwoC2PMaintenanceCodec.MaintenanceRequest(
                        credentials.merchantId(), processType, invoiceNo, amount, idempotencyId, notifyUrl
                ),
                maintenanceKeys(connection)
        );
        validateMaintenanceIdentity(response, credentials.merchantId(), processType,
                invoiceNo, amount, idempotencyId);
        return mapMaintenanceResult(request.operationType(), invoiceNo, response);
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        validateProfile(connection);
        GatewayOperationType operationType = query.operationType() == null
                ? GatewayOperationType.AUTHORIZE
                : query.operationType();
        if (operationType == GatewayOperationType.AUTHORIZE || operationType == GatewayOperationType.STATUS_QUERY) {
            return queryPaymentStatus(query, connection);
        }
        requirePrivateSandboxMaintenance(connection);
        Credentials credentials = credentials(connection);
        String invoiceNo = stableInvoice(query.providerReference());
        BigDecimal expectedAmount = query.amount() == null ? null : amount(query.amount());
        String processType = operationType == GatewayOperationType.REFUND ? "RS" : "I";
        TwoC2PMaintenanceCodec.MaintenanceResponse response = maintenanceExchange(
                new TwoC2PMaintenanceCodec.MaintenanceRequest(
                        credentials.merchantId(), processType, invoiceNo,
                        expectedAmount == null ? BigDecimal.ZERO.setScale(2) : expectedAmount,
                        null, null
                ),
                maintenanceKeys(connection)
        );
        validateMaintenanceIdentity(response, credentials.merchantId(), processType, invoiceNo,
                "RS".equals(processType) ? null : expectedAmount, null);
        if (operationType == GatewayOperationType.REFUND) {
            return mapRefundStatus(query, invoiceNo, response);
        }
        return mapMaintenanceResult(operationType, invoiceNo, response, query.gatewayOperationId());
    }

    private GatewayOperationResult queryPaymentStatus(
            GatewayOperationStatusQuery query,
            GatewayConnection connection
    ) {
        Credentials credentials = credentials(connection);
        String invoiceNo = query.providerReference() == null || query.providerReference().isBlank()
                ? invoiceNo(query.operationId())
                : stableInvoice(query.providerReference());
        BigDecimal expectedAmount = query.amount() == null ? null : amount(query.amount());
        String expectedCurrency = query.currency() == null ? null : currency(query.currency());
        JsonNode response = paymentInquiry(invoiceNo, credentials);
        validatePaymentIdentity(response, credentials.merchantId(), invoiceNo,
                expectedAmount, expectedCurrency, true);
        String responseCode = required(response, "respCode", "TWO_C2P_RESPONSE_INVALID");
        GatewayOperationStatus status = paymentStatus(responseCode);
        String publicReference = firstPresent(response.path("referenceNo").asText(null), invoiceNo);
        return new GatewayOperationResult(
                query.gatewayOperationId(),
                status,
                status == GatewayOperationStatus.SUCCEEDED
                        ? "APPROVED"
                        : status == GatewayOperationStatus.PENDING_RECONCILIATION
                        ? "TWO_C2P_" + safeCode(responseCode) + "_PENDING"
                        : "TWO_C2P_" + safeCode(responseCode),
                invoiceNo,
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
        String body = rawBody == null ? "" : rawBody.trim();
        if (body.startsWith("notifyResponse=") || looksLikeCompactJose(body)) {
            return parseMaintenanceWebhook(connection, body);
        }
        return parsePaymentWebhook(connection, body);
    }

    private GatewayWebhookEvent parsePaymentWebhook(GatewayConnection connection, String rawBody) {
        Credentials credentials = credentials(connection);
        JsonNode outer;
        try {
            outer = objectMapper.readTree(rawBody);
        } catch (JsonProcessingException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_INVALID", "2C2P webhook payload is invalid.");
        }
        String token = outer == null ? "" : outer.path("payload").asText();
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
        String invoiceNo = stableWebhookInvoice(payload.path("invoiceNo").asText());
        String responseCode = requiredWebhookValue(payload.path("respCode").asText(), "TWO_C2P_WEBHOOK_RESPONSE_MISSING");
        BigDecimal amount = webhookAmount(payload.path("amount").asText());
        String currency = webhookCurrency(payload.path("currencyCode").asText());
        String transactionReference = blankToNull(payload.path("tranRef").asText(null));
        String publicReference = firstPresent(blankToNull(payload.path("referenceNo").asText(null)), transactionReference);
        String transactionDate = payload.path("transactionDateTime").asText(null);
        String idempotencyId = blankToNull(payload.path("idempotencyID").asText(null));
        GatewayWebhookOutcome outcome = "0000".equals(responseCode)
                ? isPrivateSandbox(connection) ? GatewayWebhookOutcome.AUTHORIZED : GatewayWebhookOutcome.CAPTURED
                : isDefinitivePaymentDecline(responseCode)
                ? GatewayWebhookOutcome.DECLINED
                : GatewayWebhookOutcome.PENDING;
        return new GatewayWebhookEvent(
                boundedEventId(String.join(":", merchantId, invoiceNo,
                        transactionReference == null ? "none" : transactionReference, responseCode)),
                "payment.response.backend",
                invoiceNo,
                invoiceNo,
                publicReference,
                outcome,
                "0000".equals(responseCode) ? outcome.name() : "TWO_C2P_" + safeCode(responseCode),
                parseTransactionTime(transactionDate),
                amount,
                currency,
                idempotencyId
        );
    }

    private GatewayWebhookEvent parseMaintenanceWebhook(GatewayConnection connection, String body) {
        requirePrivateSandboxMaintenance(connection);
        String token = body;
        if (body.startsWith("notifyResponse=")) {
            if (body.indexOf('&') >= 0) {
                throw invalidWebhook("TWO_C2P_WEBHOOK_INVALID", "2C2P maintenance notification is invalid.");
            }
            try {
                token = URLDecoder.decode(body.substring("notifyResponse=".length()), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                throw invalidWebhook("TWO_C2P_WEBHOOK_INVALID", "2C2P maintenance notification encoding is invalid.");
            }
        }
        TwoC2PMaintenanceCodec.MaintenanceResponse response;
        try {
            response = maintenanceCodec.decodeResponse(token, maintenanceKeys(connection));
        } catch (GatewayBusinessException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_SIGNATURE_INVALID", "2C2P maintenance notification is invalid.");
        }
        Credentials credentials = credentials(connection);
        String invoiceNo = stableWebhookInvoice(response.invoiceNo());
        validateMaintenanceIdentityForWebhook(response, credentials.merchantId(), "R", invoiceNo);
        if (response.amount() == null) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_AMOUNT_MISSING", "2C2P maintenance notification amount is missing.");
        }
        GatewayWebhookOutcome outcome = refundOutcome(response.status(), response.responseCode());
        String publicReference = firstPresent(response.refundReferenceNo(), response.referenceNo());
        return new GatewayWebhookEvent(
                boundedEventId(String.join(":", credentials.merchantId(), invoiceNo,
                        publicReference == null ? "none" : publicReference,
                        firstPresent(response.status(), response.responseCode(), "unknown"))),
                "payment.maintenance.refund",
                invoiceNo,
                invoiceNo,
                publicReference,
                outcome,
                maintenanceCode(response),
                null,
                response.amount(),
                null,
                response.idempotencyId()
        );
    }

    private JsonNode paymentInquiry(
            String invoiceNo,
            Credentials credentials
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("merchantID", credentials.merchantId());
        payload.put("invoiceNo", invoiceNo);
        payload.put("locale", "en");
        return postSigned("/payment/4.3/paymentInquiry", payload, credentials.secretKey());
    }

    private JsonNode postSigned(String path, Map<String, Object> payload, String secretKey) {
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
        String responseToken = outer == null ? "" : outer.path("payload").asText();
        if (responseToken.isBlank()) {
            throw new GatewayBusinessException(
                    "TWO_C2P_RESPONSE_UNSIGNED",
                    "2C2P did not return the required signed response."
            );
        }
        return jwtCodec.decodeAndVerify(responseToken, secretKey);
    }

    private TwoC2PMaintenanceCodec.MaintenanceResponse maintenanceExchange(
            TwoC2PMaintenanceCodec.MaintenanceRequest request,
            TwoC2PMaintenanceCodec.KeyMaterial keys
    ) {
        String token = maintenanceCodec.encodeRequest(request, keys);
        ProviderHttpTransport.Response response = transport.postText(
                URI.create(maintenanceBaseUrl + MAINTENANCE_PATH),
                Map.of("Accept", "text/plain"),
                token
        );
        if (!response.successful()) {
            throw new GatewayUnavailableException(
                    "TWO_C2P_MAINTENANCE_UNAVAILABLE_" + response.statusCode(),
                    "2C2P maintenance is temporarily unavailable."
            );
        }
        return maintenanceCodec.decodeResponse(response.body(), keys);
    }

    private GatewayOperationResult mapMaintenanceResult(
            GatewayOperationType operationType,
            String invoiceNo,
            TwoC2PMaintenanceCodec.MaintenanceResponse response
    ) {
        return mapMaintenanceResult(operationType, invoiceNo, response, UUID.randomUUID());
    }

    private GatewayOperationResult mapMaintenanceResult(
            GatewayOperationType operationType,
            String invoiceNo,
            TwoC2PMaintenanceCodec.MaintenanceResponse response,
            UUID gatewayOperationId
    ) {
        String status = upper(response.status());
        String responseCode = upper(response.responseCode());
        GatewayOperationStatus operationStatus;
        if ("00".equals(responseCode) && terminalSuccess(operationType, status)) {
            operationStatus = GatewayOperationStatus.SUCCEEDED;
        } else if (pendingMaintenance(operationType, status, responseCode)) {
            operationStatus = GatewayOperationStatus.PENDING_RECONCILIATION;
        } else {
            operationStatus = GatewayOperationStatus.DECLINED;
        }
        return new GatewayOperationResult(
                gatewayOperationId,
                operationStatus,
                operationStatus == GatewayOperationStatus.SUCCEEDED ? "APPROVED" : maintenanceCode(response),
                invoiceNo,
                firstPresent(response.refundReferenceNo(), response.referenceNo()),
                null,
                Instant.now()
        );
    }

    private GatewayOperationResult mapRefundStatus(
            GatewayOperationStatusQuery query,
            String invoiceNo,
            TwoC2PMaintenanceCodec.MaintenanceResponse response
    ) {
        String responseCode = upper(response.responseCode());
        if (!"00".equals(responseCode)) {
            GatewayOperationStatus status = UNCERTAIN_MAINTENANCE_CODES.contains(responseCode)
                    ? GatewayOperationStatus.PENDING_RECONCILIATION
                    : GatewayOperationStatus.DECLINED;
            return new GatewayOperationResult(
                    query.gatewayOperationId(), status, maintenanceCode(response), invoiceNo,
                    query.publicTransactionReference(), null, Instant.now()
            );
        }
        TwoC2PMaintenanceCodec.Refund refund = selectRefund(response.refunds(),
                query.publicTransactionReference(), query.amount());
        if (refund == null) {
            return new GatewayOperationResult(
                    query.gatewayOperationId(), GatewayOperationStatus.PENDING_RECONCILIATION,
                    "TWO_C2P_REFUND_STATUS_PENDING", invoiceNo,
                    query.publicTransactionReference(), null, Instant.now()
            );
        }
        GatewayWebhookOutcome outcome = refundOutcome(refund.status(), response.responseCode());
        GatewayOperationStatus status = outcome == GatewayWebhookOutcome.REFUNDED
                ? GatewayOperationStatus.SUCCEEDED
                : outcome == GatewayWebhookOutcome.PENDING
                ? GatewayOperationStatus.PENDING_RECONCILIATION
                : GatewayOperationStatus.DECLINED;
        return new GatewayOperationResult(
                query.gatewayOperationId(), status,
                status == GatewayOperationStatus.SUCCEEDED ? "APPROVED" : "TWO_C2P_REFUND_" + safeCode(refund.status()),
                invoiceNo, refund.referenceNo(), null, Instant.now()
        );
    }

    private TwoC2PMaintenanceCodec.Refund selectRefund(
            List<TwoC2PMaintenanceCodec.Refund> refunds,
            String expectedReference,
            BigDecimal expectedAmount
    ) {
        if (expectedReference != null && !expectedReference.isBlank()) {
            TwoC2PMaintenanceCodec.Refund refund = refunds.stream()
                    .filter(candidate -> expectedReference.equals(candidate.referenceNo()))
                    .findFirst()
                    .orElse(null);
            if (refund != null && expectedAmount != null
                    && (refund.amount() == null || refund.amount().compareTo(expectedAmount) != 0)) {
                throw new GatewayBusinessException(
                        "TWO_C2P_REFUND_AMOUNT_MISMATCH",
                        "2C2P returned an unexpected amount for the referenced refund."
                );
            }
            return refund;
        }
        if (expectedAmount == null) {
            return refunds.size() == 1 ? refunds.getFirst() : null;
        }
        List<TwoC2PMaintenanceCodec.Refund> matches = refunds.stream()
                .filter(refund -> refund.amount() != null && refund.amount().compareTo(expectedAmount) == 0)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private boolean terminalSuccess(GatewayOperationType type, String status) {
        return switch (type) {
            case CAPTURE -> Set.of("S", "PPC", "PFC").contains(status);
            case VOID -> "V".equals(status);
            case REFUND -> "RF".equals(status);
            default -> false;
        };
    }

    private boolean pendingMaintenance(GatewayOperationType type, String status, String responseCode) {
        if (UNCERTAIN_MAINTENANCE_CODES.contains(responseCode)) {
            return true;
        }
        return switch (type) {
            case CAPTURE -> Set.of("A", "AP", "RS", "VP").contains(status);
            case VOID -> Set.of("A", "AP", "RS", "VP").contains(status);
            case REFUND -> "RP".equals(status);
            default -> true;
        };
    }

    private GatewayWebhookOutcome refundOutcome(String status, String responseCode) {
        String normalizedStatus = upper(status);
        String normalizedResponseCode = upper(responseCode);
        if (!"00".equals(normalizedResponseCode)) {
            return UNCERTAIN_MAINTENANCE_CODES.contains(normalizedResponseCode)
                    ? GatewayWebhookOutcome.PENDING
                    : GatewayWebhookOutcome.DECLINED;
        }
        if ("RP".equals(normalizedStatus)) {
            return GatewayWebhookOutcome.PENDING;
        }
        return "RF".equals(normalizedStatus)
                ? GatewayWebhookOutcome.REFUNDED
                : GatewayWebhookOutcome.DECLINED;
    }

    private GatewayOperationStatus paymentStatus(String responseCode) {
        if ("0000".equals(responseCode)) {
            return GatewayOperationStatus.SUCCEEDED;
        }
        return isDefinitivePaymentDecline(responseCode)
                ? GatewayOperationStatus.DECLINED
                : GatewayOperationStatus.PENDING_RECONCILIATION;
    }

    private boolean isDefinitivePaymentDecline(String responseCode) {
        String normalized = upper(responseCode);
        if (PAYMENT_PENDING_CODES.contains(normalized) || PAYMENT_TRANSIENT_CODES.contains(normalized)) {
            return false;
        }
        return PAYMENT_DECLINED_CODES.contains(normalized);
    }

    private void validatePaymentIdentity(
            JsonNode response,
            String merchantId,
            String invoiceNo,
            BigDecimal expectedAmount,
            String expectedCurrency,
            boolean requireIdentity
    ) {
        String actualMerchant = response.path("merchantID").asText();
        String actualInvoice = response.path("invoiceNo").asText();
        if ((requireIdentity && (actualMerchant.isBlank() || actualInvoice.isBlank()))
                || (!actualMerchant.isBlank() && !merchantId.equals(actualMerchant))
                || (!actualInvoice.isBlank() && !invoiceNo.equals(actualInvoice))) {
            throw new GatewayBusinessException("TWO_C2P_RESPONSE_IDENTITY_MISMATCH", "2C2P returned a response for another payment.");
        }
        if (expectedAmount != null) {
            String actual = response.path("amount").asText();
            if (actual.isBlank() || webhookAmount(actual).compareTo(expectedAmount) != 0) {
                throw new GatewayBusinessException("TWO_C2P_RESPONSE_AMOUNT_MISMATCH", "2C2P returned an unexpected payment amount.");
            }
        }
        if (expectedCurrency != null) {
            String actual = response.path("currencyCode").asText();
            if (actual.isBlank() || !expectedCurrency.equalsIgnoreCase(actual)) {
                throw new GatewayBusinessException("TWO_C2P_RESPONSE_CURRENCY_MISMATCH", "2C2P returned an unexpected payment currency.");
            }
        }
    }

    private void validateMaintenanceIdentity(
            TwoC2PMaintenanceCodec.MaintenanceResponse response,
            String merchantId,
            String processType,
            String invoiceNo,
            BigDecimal expectedAmount,
            String expectedIdempotency
    ) {
        if (response.responseCode() == null || response.responseCode().isBlank()
                || !processType.equalsIgnoreCase(blankToEmpty(response.processType()))
                || !invoiceNo.equals(response.invoiceNo())
                || (response.merchantId() != null && !merchantId.equals(response.merchantId()))) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_IDENTITY_MISMATCH",
                    "2C2P returned maintenance data for another request."
            );
        }
        if (expectedAmount != null && (response.amount() == null
                || response.amount().compareTo(expectedAmount) != 0)) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_AMOUNT_MISMATCH",
                    "2C2P returned an unexpected maintenance amount."
            );
        }
        if (expectedIdempotency != null && !expectedIdempotency.equals(response.idempotencyId())) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_IDEMPOTENCY_MISMATCH",
                    "2C2P returned an unexpected maintenance idempotency key."
            );
        }
    }

    private void validateMaintenanceIdentityForWebhook(
            TwoC2PMaintenanceCodec.MaintenanceResponse response,
            String merchantId,
            String processType,
            String invoiceNo
    ) {
        try {
            validateMaintenanceIdentity(response, merchantId, processType, invoiceNo, null, null);
        } catch (GatewayBusinessException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_IDENTITY_MISMATCH", "2C2P maintenance notification identity is invalid.");
        }
    }

    private TwoC2PMaintenanceCodec.KeyMaterial maintenanceKeys(GatewayConnection connection) {
        return maintenanceCodec.parseKeyMaterial(credentialResolver.requireCertificate(connection));
    }

    private Credentials credentials(GatewayConnection connection) {
        if (isPublicDemo(connection)) {
            if (demoSecretKey.isBlank()) {
                throw new GatewayBusinessException(
                        "TWO_C2P_DEMO_KEY_UNAVAILABLE",
                        "Configure the published 2C2P Singapore demo key in the runtime secret."
                );
            }
            return new Credentials(demoMerchantId, demoSecretKey, null, null, null);
        }
        String configured = credentialResolver.requireCredential(connection);
        try {
            JsonNode json = objectMapper.readTree(configured);
            if (json == null || !json.isObject()) {
                throw new JsonProcessingException("not an object") { };
            }
            return new Credentials(
                    json.path("merchantId").asText().trim(),
                    json.path("secretKey").asText().trim(),
                    json.path("validationInvoiceNo").asText().trim(),
                    json.path("validationAmount").asText().trim(),
                    json.path("validationCurrency").asText().trim()
            );
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("TWO_C2P_CREDENTIAL_INVALID", "2C2P credentials must be a JSON object.");
        }
    }

    private ValidationProbe validationProbe(Credentials credentials) {
        if (credentials.validationInvoiceNo().isBlank()
                || credentials.validationAmount().isBlank()
                || credentials.validationCurrency().isBlank()) {
            throw new GatewayBusinessException(
                    "TWO_C2P_VALIDATION_PROBE_REQUIRED",
                    "2C2P private sandbox credentials require validationInvoiceNo, validationAmount, and validationCurrency for a real PREAUTH transaction."
            );
        }
        try {
            String invoiceNo = stableInvoice(credentials.validationInvoiceNo());
            BigDecimal validationAmount = amount(new BigDecimal(credentials.validationAmount()));
            if (validationAmount.signum() <= 0) {
                throw new IllegalArgumentException("validation amount must be positive");
            }
            return new ValidationProbe(invoiceNo, validationAmount, currency(credentials.validationCurrency()));
        } catch (RuntimeException exception) {
            throw new GatewayBusinessException(
                    "TWO_C2P_VALIDATION_PROBE_INVALID",
                    "2C2P validationInvoiceNo must be 1-50 alphanumeric characters, validationAmount must be positive with at most two decimals, and validationCurrency must be a three-letter code."
            );
        }
    }

    private void requirePrivateSandboxMaintenance(GatewayConnection connection) {
        if (!isPrivateSandbox(connection)) {
            throw new GatewayBusinessException(
                    "TWO_C2P_MAINTENANCE_UNAVAILABLE",
                    "2C2P maintenance is enabled only for a validated private sandbox connection."
            );
        }
    }

    private boolean isPublicDemo(GatewayConnection connection) {
        return connection.environment() == GatewayEnvironment.SANDBOX
                && DEMO_PROFILE.equalsIgnoreCase(connection.endpointProfile());
    }

    private boolean isPrivateSandbox(GatewayConnection connection) {
        return connection.environment() == GatewayEnvironment.SANDBOX
                && PRIVATE_SANDBOX_PROFILE.equalsIgnoreCase(connection.endpointProfile());
    }

    private void validateProfile(GatewayConnection connection) {
        if (connection.environment() == GatewayEnvironment.SANDBOX && isPublicDemo(connection)) {
            return;
        }
        String expected = connection.environment() == GatewayEnvironment.SANDBOX
                ? PRIVATE_SANDBOX_PROFILE
                : "2c2p-production";
        if (!expected.equalsIgnoreCase(connection.endpointProfile())) {
            throw new GatewayBusinessException("TWO_C2P_ENDPOINT_PROFILE_INVALID", "2C2P endpoint profile does not match the environment.");
        }
    }

    private String invoiceNo(String value) {
        String original = value == null ? "" : value.trim();
        String normalized = original.replaceAll("[^A-Za-z0-9]", "");
        if (normalized.isBlank()) {
            throw new GatewayBusinessException("TWO_C2P_INVOICE_INVALID", "2C2P requires a stable alphanumeric operation identifier.");
        }
        if (original.matches("^[A-Za-z0-9]{1,50}$")) {
            return original;
        }
        String digest = sha256(original).substring(0, 16);
        String prefix = normalized.substring(0, Math.min(34, normalized.length()));
        return prefix + digest;
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String stableInvoice(String value) {
        if (value == null || !value.matches("^[A-Za-z0-9]{1,50}$")) {
            throw new GatewayBusinessException("TWO_C2P_INVOICE_INVALID", "A stable 2C2P invoice reference is required.");
        }
        return value;
    }

    private String stableWebhookInvoice(String value) {
        try {
            return stableInvoice(value);
        } catch (GatewayBusinessException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_INVOICE_INVALID", "2C2P webhook invoice is invalid.");
        }
    }

    private BigDecimal amount(BigDecimal value) {
        try {
            if (value == null || value.signum() < 0) {
                throw new ArithmeticException();
            }
            return value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new GatewayBusinessException("TWO_C2P_AMOUNT_INVALID", "2C2P requires a non-negative amount with at most two decimals.");
        }
    }

    private BigDecimal webhookAmount(String value) {
        try {
            if (value == null || !value.matches("^\\d{1,16}(?:\\.\\d{1,2})?$")) {
                throw new NumberFormatException();
            }
            return new BigDecimal(value).setScale(2);
        } catch (RuntimeException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_AMOUNT_INVALID", "2C2P webhook amount is invalid.");
        }
    }

    private String currency(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z]{3}$")) {
            throw new GatewayBusinessException("TWO_C2P_CURRENCY_INVALID", "2C2P requires a three-letter currency code.");
        }
        return normalized;
    }

    private String webhookCurrency(String value) {
        try {
            return currency(value);
        } catch (GatewayBusinessException exception) {
            throw invalidWebhook("TWO_C2P_WEBHOOK_CURRENCY_INVALID", "2C2P webhook currency is invalid.");
        }
    }

    private String idempotencyId(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^[A-Za-z0-9._:-]{1,100}$")) {
            throw new GatewayBusinessException(
                    "TWO_C2P_IDEMPOTENCY_INVALID",
                    "2C2P requires a stable idempotency key of at most 100 safe characters."
            );
        }
        return normalized;
    }

    private String validationInvoice(UUID connectionId) {
        String suffix = connectionId == null
                ? UUID.randomUUID().toString().replace("-", "")
                : connectionId.toString().replace("-", "");
        return "EHVALIDATE" + suffix.substring(0, Math.min(32, suffix.length()));
    }

    private ConnectionValidation invalidValidation(String code, String message) {
        return new ConnectionValidation(false, code, message, Set.of(), "2c2p-v4.3");
    }

    private String maintenanceCode(TwoC2PMaintenanceCodec.MaintenanceResponse response) {
        return "TWO_C2P_" + safeCode(firstPresent(response.responseCode(), response.status(), "REQUEST_FAILED"));
    }

    private String required(JsonNode node, String name, String code) {
        String value = node.path(name).asText();
        if (value.isBlank()) {
            throw new GatewayBusinessException(code, "2C2P returned an incomplete signed response.");
        }
        return value;
    }

    private void rejectMismatchedOptional(JsonNode node, String name, String expected) {
        String actual = node.path(name).asText();
        if (!actual.isBlank() && !expected.equals(actual)) {
            throw new GatewayBusinessException("TWO_C2P_RESPONSE_IDENTITY_MISMATCH", "2C2P returned a response for another payment.");
        }
    }

    private String safeCode(String value) {
        String code = value == null || value.isBlank() ? "REQUEST_FAILED" : value.replaceAll("[^A-Za-z0-9]", "_");
        return code.toUpperCase(Locale.ROOT);
    }

    private String upper(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

    private boolean looksLikeCompactJose(String value) {
        return value != null && value.matches("^[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String firstPresent(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
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
            String merchantId,
            String secretKey,
            String validationInvoiceNo,
            String validationAmount,
            String validationCurrency
    ) {
        boolean valid() {
            return merchantId != null && merchantId.matches("^[A-Za-z0-9_-]{1,50}$")
                    && secretKey != null && secretKey.length() >= 32;
        }
    }

    private record ValidationProbe(String invoiceNo, BigDecimal amount, String currency) {
    }
}
