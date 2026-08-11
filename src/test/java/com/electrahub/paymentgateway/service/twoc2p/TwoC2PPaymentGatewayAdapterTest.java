package com.electrahub.paymentgateway.service.twoc2p;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
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
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TwoC2PPaymentGatewayAdapterTest {

    private static final String SIGNING_KEY = "test-demo-key-abcdefghijklmnopqrstuvwxyz-0123456789";
    private static final String VALIDATION_INVOICE = "validationinvoice123";

    private HttpServer server;
    private TwoC2PPaymentGatewayAdapter adapter;
    private ObjectMapper objectMapper;
    private JwtHs256Codec jwtCodec;
    private TestCredentialResolver credentialResolver;
    private RSAPrivateKey providerPrivateKey;
    private RSAPublicKey merchantPublicKey;
    private final List<MaintenanceCall> maintenanceCalls = new ArrayList<>();
    private final Map<String, String> maintenanceStatuses = new HashMap<>();
    private JsonNode lastPaymentTokenPayload;
    private JsonNode lastInquiryPayload;
    private int paymentTokenRequests;
    private int inquiryRequests;
    private String responseSigningKey;
    private boolean unsignedPaymentResponse;
    private boolean tamperMaintenanceResponse;
    private String maintenanceInvoiceOverride;
    private String maintenanceAmountOverride;
    private String maintenanceProcessOverride;
    private String maintenanceIdempotencyOverride;
    private String maintenanceResponseCode;
    private String paymentCurrencyOverride;
    private String paymentInquiryResponseCode;
    private String refundListAmountOverride;
    private boolean includeValidationProbe = true;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper();
        jwtCodec = new JwtHs256Codec(objectMapper);
        responseSigningKey = SIGNING_KEY;
        KeyPair merchantKeys = rsaKeyPair();
        KeyPair providerKeys = rsaKeyPair();
        X509Certificate providerCertificate = selfSignedCertificate(providerKeys, "2C2P Test Provider");
        String merchantPrivatePem = pem("PRIVATE KEY", merchantKeys.getPrivate().getEncoded());
        String providerCertificatePem = pem("CERTIFICATE", providerCertificate.getEncoded());
        providerPrivateKey = (RSAPrivateKey) providerKeys.getPrivate();
        merchantPublicKey = (RSAPublicKey) merchantKeys.getPublic();
        String certificateSecret = objectMapper.writeValueAsString(Map.of(
                "merchantPrivateKeyPem", merchantPrivatePem,
                "twoC2PPublicCertificatePem", providerCertificatePem
        ));
        credentialResolver = new TestCredentialResolver(certificateSecret);

        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/payment/4.3/paymentToken", this::paymentToken);
        server.createContext("/payment/4.3/paymentInquiry", this::paymentInquiry);
        server.createContext(TwoC2PPaymentGatewayAdapter.MAINTENANCE_PATH, this::maintenance);
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new TwoC2PPaymentGatewayAdapter(
                new ProviderHttpTransport(), credentialResolver, objectMapper,
                endpoint, endpoint, "JT01", SIGNING_KEY,
                "https://api.electrahub.net/payment-gateway/api/v1/gateway/webhooks/test"
        );
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void publicDemoValidatesWithoutPrivateSecretsButCannotAdvertiseMaintenance() {
        var validation = adapter.validate(demoConnection());

        assertThat(validation.valid()).isTrue();
        assertThat(validation.capabilities()).containsExactlyInAnyOrder(
                GatewayCapability.AUTHORIZE,
                GatewayCapability.STATUS_QUERY,
                GatewayCapability.THREE_DS_SCA
        );
        assertThat(validation.capabilities()).doesNotContain(
                GatewayCapability.MANUAL_CAPTURE, GatewayCapability.CAPTURE,
                GatewayCapability.VOID, GatewayCapability.REFUND
        );
        assertThat(credentialResolver.credentialRequests).isZero();
        assertThat(credentialResolver.certificateRequests).isZero();
        assertThat(maintenanceCalls).isEmpty();
        assertThat(paymentTokenRequests).isEqualTo(1);
        assertThat(inquiryRequests).isZero();
        assertThat(lastPaymentTokenPayload.path("merchantID").asText()).isEqualTo("JT01");
        assertThat(lastPaymentTokenPayload.path("currencyCode").asText()).isEqualTo("SGD");
        assertThat(lastPaymentTokenPayload.path("amount").asText()).isEqualTo("1.00");
    }

    @Test
    void publicDemoValidationRejectsResponseSignedWithAnotherKey() {
        responseSigningKey = "different-signing-key-abcdefghijklmnopqrstuvwxyz-0123456789";

        var validation = adapter.validate(demoConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_CREDENTIAL_REJECTED");
        assertThat(validation.capabilities()).isEmpty();
    }

    @Test
    void privateSandboxAdvertisesMaintenanceOnlyAfterRealJoseExchange() {
        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isTrue();
        assertThat(validation.capabilities()).contains(
                GatewayCapability.MANUAL_CAPTURE,
                GatewayCapability.CAPTURE,
                GatewayCapability.VOID,
                GatewayCapability.REFUND
        );
        assertThat(credentialResolver.certificateRequests).isEqualTo(1);
        assertThat(maintenanceCalls).singleElement().satisfies(call -> {
            assertThat(call.contentType()).startsWith("text/plain");
            assertThat(call.processType()).isEqualTo("I");
            assertThat(call.invoiceNo()).isEqualTo(VALIDATION_INVOICE);
            assertThat(call.actionAmount()).isEqualByComparingTo("10.00");
        });
        assertThat(lastInquiryPayload.path("invoiceNo").asText()).isEqualTo(VALIDATION_INVOICE);
    }

    @Test
    void rejectsPrivateCredentialsWithoutARealValidationTransaction() {
        includeValidationProbe = false;

        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_VALIDATION_PROBE_REQUIRED");
        assertThat(validation.capabilities()).isEmpty();
        assertThat(inquiryRequests).isZero();
        assertThat(maintenanceCalls).isEmpty();
    }

    @Test
    void rejectsPrivateCapabilitiesWhenPaymentProbeIdentityDoesNotMatch() {
        paymentCurrencyOverride = "EUR";

        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_VALIDATION_PROBE_MISMATCH");
        assertThat(validation.capabilities()).isEmpty();
        assertThat(maintenanceCalls).isEmpty();
    }

    @Test
    void rejectsPrivateCapabilitiesWhenMaintenanceProbeAmountDoesNotMatch() {
        maintenanceAmountOverride = "9.99";

        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_VALIDATION_PROBE_MISMATCH");
        assertThat(validation.capabilities()).isEmpty();
    }

    @Test
    void rejectsPrivateCapabilitiesWhenProtectedMaintenanceResponseIsTampered() {
        tamperMaintenanceResponse = true;

        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_MAINTENANCE_KEY_REJECTED");
        assertThat(validation.capabilities()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"16", "18", "97", "98"})
    void rejectsSignedProtectedProbeRejectionCodes(String responseCode) {
        maintenanceResponseCode = responseCode;

        var validation = adapter.validate(privateConnection());

        assertThat(validation.valid()).isFalse();
        assertThat(validation.code()).isEqualTo("TWO_C2P_MAINTENANCE_PROBE_REJECTED");
        assertThat(validation.capabilities()).isEmpty();
    }

    @Test
    void createsSignedHostedPreauthorizationWithStableInvoiceReference() {
        var result = adapter.execute(
                request(GatewayOperationType.AUTHORIZE, "electrahub://payment/return"),
                privateConnection()
        );

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThat(result.providerReference()).isEqualTo(lastPaymentTokenPayload.path("invoiceNo").asText());
        assertThat(result.providerReference()).matches("^[A-Za-z0-9]{1,50}$");
        assertThat(result.action().url()).contains("sandbox-pgw-ui.2c2p.com");
        assertThat(lastPaymentTokenPayload.path("paymentChannel").get(0).asText()).isEqualTo("CC");
        assertThat(lastPaymentTokenPayload.path("transactionMode").asText()).isEqualTo("PREAUTH");
        assertThat(lastPaymentTokenPayload.path("schemeReturnUrl").asText()).isEqualTo("electrahub://payment/return");
    }

    @Test
    void requiresSignedPaymentApiResponses() {
        unsignedPaymentResponse = true;

        assertThatThrownBy(() -> adapter.execute(request(GatewayOperationType.AUTHORIZE), demoConnection()))
                .isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_RESPONSE_UNSIGNED");
    }

    @Test
    void executesSettleVoidAndRefundWithStableIdempotencyAndAmount() {
        var capture = adapter.execute(request(GatewayOperationType.CAPTURE), privateConnection());
        var voided = adapter.execute(request(GatewayOperationType.VOID), privateConnection());
        var refund = adapter.execute(request(GatewayOperationType.REFUND), privateConnection());

        assertThat(capture.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(voided.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(refund.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(refund.publicTransactionReference()).isEqualTo("refund-123");
        assertThat(maintenanceCalls).extracting(MaintenanceCall::processType)
                .containsExactly("S", "V", "R");
        assertThat(maintenanceCalls).allSatisfy(call -> {
            assertThat(call.invoiceNo()).isEqualTo("operation123");
            assertThat(call.actionAmount()).isEqualByComparingTo("10.00");
            assertThat(call.idempotencyId()).isEqualTo("idem-123");
        });
        assertThat(maintenanceCalls.getLast().notifyUrl()).contains(privateConnection().id().toString());
    }

    @Test
    void readyForSettlementIsPendingUntilInquiryConfirmsCapture() {
        maintenanceStatuses.put("S", "RS");
        var mutation = adapter.execute(request(GatewayOperationType.CAPTURE), privateConnection());
        maintenanceStatuses.put("I", "S");
        var query = adapter.queryStatus(statusQuery(GatewayOperationType.CAPTURE, null), privateConnection());

        assertThat(mutation.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(query.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(maintenanceCalls).extracting(MaintenanceCall::processType).containsExactly("S", "I");
    }

    @Test
    void idempotencyCollisionNeverBecomesSuccessFromTransactionStatusAlone() {
        maintenanceResponseCode = "50";
        maintenanceStatuses.put("S", "S");

        var result = adapter.execute(request(GatewayOperationType.CAPTURE), privateConnection());

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(result.code()).isEqualTo("TWO_C2P_50");
    }

    @Test
    void refundStatusUsesTheIndividualRefundReference() {
        var result = adapter.queryStatus(
                statusQuery(GatewayOperationType.REFUND, "refund-123"),
                privateConnection()
        );

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(result.publicTransactionReference()).isEqualTo("refund-123");
        assertThat(maintenanceCalls).extracting(MaintenanceCall::processType).containsExactly("RS");
    }

    @Test
    void refundStatusRejectsMatchingReferenceWithWrongAmount() {
        refundListAmountOverride = "9.99";

        assertThatThrownBy(() -> adapter.queryStatus(
                statusQuery(GatewayOperationType.REFUND, "refund-123"), privateConnection()
        )).isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_REFUND_AMOUNT_MISMATCH");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0999", "4050", "4091", "4096", "5002", "5998",
            "9993", "9994", "9995", "9996", "9997", "9998", "9999", "7777"
    })
    void transientAndUnknownInquiryCodesRemainPending(String responseCode) {
        paymentInquiryResponseCode = responseCode;

        var result = adapter.queryStatus(
                statusQuery(GatewayOperationType.AUTHORIZE, null), privateConnection()
        );

        assertThat(result.status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
        assertThat(result.code()).isEqualTo("TWO_C2P_" + responseCode + "_PENDING");
    }

    @Test
    void rejectsSignedMaintenanceResponseWithWrongStableFields() {
        maintenanceAmountOverride = "11.00";

        assertThatThrownBy(() -> adapter.execute(request(GatewayOperationType.REFUND), privateConnection()))
                .isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_MAINTENANCE_AMOUNT_MISMATCH");
    }

    @Test
    void rejectsSignedMaintenanceResponseForAnotherInvoice() {
        maintenanceInvoiceOverride = "anotherinvoice";

        assertThatThrownBy(() -> adapter.execute(request(GatewayOperationType.VOID), privateConnection()))
                .isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_MAINTENANCE_IDENTITY_MISMATCH");
    }

    @Test
    void rejectsSignedPaymentInquiryWithWrongCurrency() {
        paymentCurrencyOverride = "EUR";

        assertThatThrownBy(() -> adapter.queryStatus(
                statusQuery(GatewayOperationType.AUTHORIZE, null), privateConnection()
        )).isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_RESPONSE_CURRENCY_MISMATCH");
    }

    @Test
    void verifiesEncryptedAsyncRefundNotification() {
        String xml = responseXml("R", "operation123", "10.00", "RF", "idem-123");
        String token = TwoC2PMaintenanceCodec.encryptThenSign(xml, merchantPublicKey, providerPrivateKey);
        String body = "notifyResponse=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        var event = adapter.parseWebhook(privateConnection(), body, Map.of());

        assertThat(event.providerReference()).isEqualTo("operation123");
        assertThat(event.publicTransactionReference()).isEqualTo("refund-123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.REFUNDED);
        assertThat(event.amount()).isEqualByComparingTo("10.00");
        assertThat(event.idempotencyKey()).isEqualTo("idem-123");
    }

    @Test
    void acceptsProtectedRefundNotificationWithoutOptionalIdempotencyId() {
        String xml = responseXml("R", "operation123", "10.00", "RF", null);
        String token = TwoC2PMaintenanceCodec.encryptThenSign(xml, merchantPublicKey, providerPrivateKey);

        var event = adapter.parseWebhook(privateConnection(), "notifyResponse=" + token, Map.of());

        assertThat(event.providerReference()).isEqualTo("operation123");
        assertThat(event.publicTransactionReference()).isEqualTo("refund-123");
        assertThat(event.amount()).isEqualByComparingTo("10.00");
        assertThat(event.idempotencyKey()).isNull();
    }

    @Test
    void malformedRefundNotificationEncodingIsAWebhookVerificationFailure() {
        assertThatThrownBy(() -> adapter.parseWebhook(
                privateConnection(), "notifyResponse=%not-hex", Map.of()
        )).isInstanceOfSatisfying(GatewayWebhookVerificationException.class, exception ->
                assertThat(exception.code()).isEqualTo("TWO_C2P_WEBHOOK_INVALID")
        );
    }

    @Test
    void verifiesPaymentCallbackAmountCurrencyAndPreauthorizationOutcome() throws Exception {
        String token = jwtCodec.encode(Map.of(
                "merchantID", "PRIVATE01",
                "invoiceNo", "operation123",
                "tranRef", "transaction-321",
                "referenceNo", "reference-123",
                "respCode", "0000",
                "amount", "10.00",
                "currencyCode", "SGD",
                "idempotencyID", "idem-123",
                "transactionDateTime", "20260731143000"
        ), SIGNING_KEY);
        String body = objectMapper.writeValueAsString(Map.of("payload", token));

        var event = adapter.parseWebhook(privateConnection(), body, Map.of());

        assertThat(event.providerReference()).isEqualTo("operation123");
        assertThat(event.outcome()).isEqualTo(GatewayWebhookOutcome.AUTHORIZED);
        assertThat(event.amount()).isEqualByComparingTo("10.00");
        assertThat(event.currency()).isEqualTo("SGD");
    }

    @Test
    void rejectsUnsignedBackendPaymentResponse() {
        assertThatThrownBy(() -> adapter.parseWebhook(
                demoConnection(), "{\"payload\":\"not-a-signed-token\"}", Map.of()
        )).isInstanceOf(GatewayWebhookVerificationException.class);
    }

    @Test
    void rejectsExternalEntitiesInMaintenanceXml() {
        String xml = "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>"
                + "<PaymentProcessResponse><respCode>00</respCode><processType>R</processType>"
                + "<invoiceNo>&e;</invoiceNo></PaymentProcessResponse>";
        TwoC2PMaintenanceCodec codec = new TwoC2PMaintenanceCodec(objectMapper);

        assertThatThrownBy(() -> codec.parseResponseXml(xml))
                .isInstanceOf(GatewayBusinessException.class)
                .extracting(exception -> ((GatewayBusinessException) exception).code())
                .isEqualTo("TWO_C2P_MAINTENANCE_RESPONSE_INVALID");
    }

    private void paymentToken(HttpExchange exchange) throws IOException {
        lastPaymentTokenPayload = requestPayload(exchange);
        paymentTokenRequests++;
        respondPayment(exchange, Map.of(
                "merchantID", lastPaymentTokenPayload.path("merchantID").asText(),
                "invoiceNo", lastPaymentTokenPayload.path("invoiceNo").asText(),
                "paymentToken", "token-demo",
                "webPaymentUrl", "https://sandbox-pgw-ui.2c2p.com/pay/demo",
                "respCode", "0000",
                "respDesc", "Success"
        ));
    }

    private void paymentInquiry(HttpExchange exchange) throws IOException {
        lastInquiryPayload = requestPayload(exchange);
        inquiryRequests++;
        String invoiceNo = lastInquiryPayload.path("invoiceNo").asText();
        respondPayment(exchange, Map.of(
                "merchantID", lastInquiryPayload.path("merchantID").asText(),
                "invoiceNo", invoiceNo,
                "referenceNo", "reference-demo",
                "amount", "10.00",
                "currencyCode", paymentCurrencyOverride == null ? "SGD" : paymentCurrencyOverride,
                "respCode", invoiceNo.startsWith("EHVALIDATE")
                        ? "2001"
                        : paymentInquiryResponseCode == null ? "0000" : paymentInquiryResponseCode,
                "respDesc", invoiceNo.startsWith("EHVALIDATE") ? "Payment not found" : "Success"
        ));
    }

    private void maintenance(HttpExchange exchange) throws IOException {
        try {
            String token = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String xml = TwoC2PMaintenanceCodec.verifyAndDecrypt(token, merchantPublicKey, providerPrivateKey);
            Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            String processType = text(document, "processType");
            String invoiceNo = text(document, "invoiceNo");
            String actionAmount = text(document, "actionAmount");
            String idempotencyId = optionalText(document, "idempotencyID");
            maintenanceCalls.add(new MaintenanceCall(
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    processType,
                    invoiceNo,
                    new BigDecimal(actionAmount),
                    idempotencyId,
                    optionalText(document, "notifyURL")
            ));
            String responseProcess = maintenanceProcessOverride == null ? processType : maintenanceProcessOverride;
            String responseInvoice = maintenanceInvoiceOverride == null ? invoiceNo : maintenanceInvoiceOverride;
            String responseAmount = maintenanceAmountOverride == null ? actionAmount : maintenanceAmountOverride;
            String responseIdempotency = maintenanceIdempotencyOverride == null ? idempotencyId : maintenanceIdempotencyOverride;
            String defaultStatus = switch (processType) {
                case "I" -> "A";
                case "S", "RS" -> "S";
                case "V" -> "V";
                case "R" -> "RF";
                default -> "PF";
            };
            String responseXml = responseXml(responseProcess, responseInvoice, responseAmount,
                    maintenanceStatuses.getOrDefault(processType, defaultStatus), responseIdempotency);
            String response = TwoC2PMaintenanceCodec.encryptThenSign(
                    responseXml, merchantPublicKey, providerPrivateKey
            );
            if (tamperMaintenanceResponse) {
                int index = response.length() - 2;
                response = response.substring(0, index)
                        + (response.charAt(index) == 'A' ? 'B' : 'A')
                        + response.substring(index + 1);
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (Exception exception) {
            byte[] bytes = "invalid test exchange".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    private String responseXml(
            String processType,
            String invoiceNo,
            String amount,
            String status,
            String idempotencyId
    ) {
        String idempotency = idempotencyId == null ? "" : "<idempotencyID>" + idempotencyId + "</idempotencyID>";
        String refund = "R".equals(processType)
                ? "<refundReferenceNo>refund-123</refundReferenceNo>"
                : "RS".equals(processType)
                ? "<refundList><refund referenceNo=\"refund-123\" status=\"RF\" amount=\""
                    + (refundListAmountOverride == null ? "10.00" : refundListAmountOverride)
                    + "\"/></refundList>"
                : "";
        String responseCode = maintenanceResponseCode == null ? "00" : maintenanceResponseCode;
        return "<PaymentProcessResponse><version>4.3</version><merchantID>PRIVATE01</merchantID>"
                + "<respCode>" + responseCode + "</respCode><respDesc>Success</respDesc><processType>" + processType + "</processType>"
                + "<invoiceNo>" + invoiceNo + "</invoiceNo><amount>" + amount + "</amount>"
                + "<status>" + status + "</status><referenceNo>reference-123</referenceNo>"
                + refund + idempotency + "</PaymentProcessResponse>";
    }

    private JsonNode requestPayload(HttpExchange exchange) throws IOException {
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonNode outer = objectMapper.readTree(request);
        return jwtCodec.decodeAndVerify(outer.path("payload").asText(), SIGNING_KEY);
    }

    private void respondPayment(HttpExchange exchange, Map<String, Object> payload) throws IOException {
        String body = unsignedPaymentResponse
                ? objectMapper.writeValueAsString(payload)
                : objectMapper.writeValueAsString(Map.of("payload", jwtCodec.encode(payload, responseSigningKey)));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private GatewayConnection demoConnection() {
        return connection(TwoC2PPaymentGatewayAdapter.DEMO_PROFILE, false, false);
    }

    private GatewayConnection privateConnection() {
        return connection(TwoC2PPaymentGatewayAdapter.PRIVATE_SANDBOX_PROFILE, true, true);
    }

    private GatewayConnection connection(String profile, boolean credential, boolean certificate) {
        return new GatewayConnection(
                UUID.fromString("6c070d32-a931-4f7b-b37e-6e309f63b21f"),
                GatewayProvider.TWO_C2P,
                GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY,
                "2c2p-v4.3",
                profile,
                Set.of(),
                credential,
                false,
                certificate,
                null,
                null,
                null,
                1,
                Instant.now(),
                Instant.now()
        );
    }

    private GatewayOperationRequest request(GatewayOperationType type) {
        return request(type, "https://driver.electrahub.net/payments/return");
    }

    private GatewayOperationRequest request(GatewayOperationType type, String returnUrl) {
        return new GatewayOperationRequest(
                UUID.randomUUID(), "payment-intent-123", null, "operation-123", "idem-123", type,
                new BigDecimal("10.00"), "SGD", "account-123", null, null, "operation123",
                returnUrl, Instant.now()
        );
    }

    private GatewayOperationStatusQuery statusQuery(GatewayOperationType type, String publicReference) {
        return new GatewayOperationStatusQuery(
                UUID.randomUUID(), "operation-123", "idem-123", "operation123",
                type, publicReference, new BigDecimal("10.00"), "SGD"
        );
    }

    private KeyPair rsaKeyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private X509Certificate selfSignedCertificate(KeyPair keys, String commonName) throws Exception {
        Instant now = Instant.now();
        X500Name subject = new X500Name("CN=" + commonName);
        var builder = new JcaX509v3CertificateBuilder(
                subject,
                new BigInteger(160, new SecureRandom()).abs(),
                Date.from(now.minus(Duration.ofDays(1))),
                Date.from(now.plus(Duration.ofDays(365))),
                subject,
                keys.getPublic()
        );
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        certificate.verify(keys.getPublic());
        return certificate;
    }

    private String pem(String type, byte[] encoded) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        return "-----BEGIN " + type + "-----\n" + body + "\n-----END " + type + "-----";
    }

    private String text(Document document, String name) {
        return document.getElementsByTagName(name).item(0).getTextContent();
    }

    private String optionalText(Document document, String name) {
        return document.getElementsByTagName(name).getLength() == 0 ? null : text(document, name);
    }

    private final class TestCredentialResolver implements ProviderCredentialResolver {
        private final String certificateSecret;
        private int credentialRequests;
        private int certificateRequests;

        private TestCredentialResolver(String certificateSecret) {
            this.certificateSecret = certificateSecret;
        }

        @Override
        public String requireCredential(GatewayConnection connection) {
            credentialRequests++;
            if (!includeValidationProbe) {
                return "{\"merchantId\":\"PRIVATE01\",\"secretKey\":\"" + SIGNING_KEY + "\"}";
            }
            return "{\"merchantId\":\"PRIVATE01\",\"secretKey\":\"" + SIGNING_KEY
                    + "\",\"validationInvoiceNo\":\"" + VALIDATION_INVOICE
                    + "\",\"validationAmount\":\"10.00\",\"validationCurrency\":\"SGD\"}";
        }

        @Override
        public String requireCertificate(GatewayConnection connection) {
            certificateRequests++;
            return certificateSecret;
        }

        @Override
        public Optional<String> webhookSecret(GatewayConnection connection) {
            return Optional.empty();
        }
    }

    private record MaintenanceCall(
            String contentType,
            String processType,
            String invoiceNo,
            BigDecimal actionAmount,
            String idempotencyId,
            String notifyUrl
    ) {
    }
}
