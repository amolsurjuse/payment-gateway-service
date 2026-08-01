package com.electrahub.paymentgateway.domain;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Provider-neutral contracts. Secret values and raw payment credentials are intentionally absent. */
public final class GatewayContracts {

    private GatewayContracts() {
    }

    public enum GatewayProvider {
        MOCK,
        STRIPE,
        MOLLIE,
        ADYEN,
        RAZORPAY,
        TWO_C2P
    }

    public enum GatewayEnvironment {
        SANDBOX,
        PRODUCTION
    }

    public enum GatewayConnectionStatus {
        DRAFT,
        VALIDATING,
        READY,
        ACTIVE,
        DISABLED,
        RETIRED
    }

    public enum MerchantAccountStatus {
        DRAFT,
        READY,
        ACTIVE,
        DISABLED,
        RETIRED
    }

    public enum PaymentChannel {
        MOBILE,
        WEB,
        TERMINAL
    }

    public enum PaymentMethodType {
        CARD_ON_FILE,
        CARD_PRESENT,
        HOSTED_CHECKOUT
    }

    public enum GatewayCapability {
        AUTHORIZE,
        MANUAL_CAPTURE,
        CAPTURE,
        PARTIAL_CAPTURE,
        VOID,
        REFUND,
        STATUS_QUERY,
        CARD_PRESENT,
        THREE_DS_SCA,
        SETTLEMENT_REPORTS,
        PAYOUT_REPORTS
    }

    public enum GatewayOperationType {
        AUTHORIZE,
        VOID,
        CAPTURE,
        REFUND,
        STATUS_QUERY
    }

    public enum GatewayOperationStatus {
        SUCCEEDED,
        DECLINED,
        ACTION_REQUIRED,
        PENDING_RECONCILIATION,
        FAILED
    }

    public enum PaymentMethodEnrollmentStatus {
        CREATED,
        SUCCEEDED,
        FAILED,
        EXPIRED
    }

    public enum GatewayActionType {
        REDIRECT,
        THREE_DS,
        SDK,
        QR_CODE
    }

    public enum GatewayWebhookOutcome {
        AUTHORIZED,
        CAPTURED,
        VOIDED,
        REFUNDED,
        DECLINED,
        ACTION_REQUIRED,
        PENDING
    }

    public record GatewayConnection(
            UUID id,
            GatewayProvider provider,
            GatewayEnvironment environment,
            GatewayConnectionStatus status,
            String adapterVersion,
            String endpointProfile,
            Set<GatewayCapability> capabilities,
            boolean credentialConfigured,
            boolean webhookSecretConfigured,
            boolean certificateConfigured,
            Instant validatedAt,
            Instant lastHealthAt,
            String lastErrorCode,
            int configurationVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        public GatewayConnection {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }

    public record MerchantAccount(
            UUID id,
            UUID connectionId,
            String enterpriseId,
            String networkId,
            String legalEntityReference,
            String providerMerchantReference,
            String merchantCountry,
            String settlementCurrency,
            Set<String> supportedPresentmentCurrencies,
            MerchantAccountStatus status,
            int configurationVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        public MerchantAccount {
            supportedPresentmentCurrencies = supportedPresentmentCurrencies == null
                    ? Set.of()
                    : Set.copyOf(supportedPresentmentCurrencies);
        }
    }

    public record PaymentRoute(
            UUID id,
            UUID merchantAccountId,
            UUID connectionId,
            String chargingCountry,
            String presentmentCurrency,
            String settlementCurrency,
            PaymentChannel channel,
            PaymentMethodType paymentMethod,
            int priority,
            boolean enabled,
            Set<GatewayCapability> requiredCapabilities,
            Instant effectiveFrom,
            Instant effectiveTo,
            int configurationVersion,
            Instant createdAt,
            Instant updatedAt
    ) {
        public PaymentRoute {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    public record CreateGatewayConnectionRequest(
            @NotNull GatewayProvider provider,
            @NotNull GatewayEnvironment environment,
            @NotBlank @Size(max = 80) String endpointProfile,
            @Size(max = 256) String credentialSecretReference,
            @Size(max = 256) String webhookSecretReference,
            @Size(max = 256) String certificateSecretReference,
            Set<GatewayCapability> requestedCapabilities
    ) {
        public CreateGatewayConnectionRequest {
            requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
        }
    }

    /**
     * Secret references are write-only. A null value preserves the configured reference; the
     * public response exposes only whether a reference is configured.
     */
    public record UpdateGatewayConnectionRequest(
            @NotNull @Positive Integer expectedConfigurationVersion,
            @NotBlank @Size(max = 80) String endpointProfile,
            @Size(max = 256) String credentialSecretReference,
            @Size(max = 256) String webhookSecretReference,
            @Size(max = 256) String certificateSecretReference
    ) {
    }

    public record CreateMerchantAccountRequest(
            @NotNull UUID connectionId,
            @Size(max = 80) String enterpriseId,
            @Size(max = 80) String networkId,
            @NotBlank @Size(max = 160) String legalEntityReference,
            @NotBlank @Size(max = 160) String providerMerchantReference,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String merchantCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String settlementCurrency,
            Set<@Pattern(regexp = "^[A-Za-z]{3}$") String> supportedPresentmentCurrencies
    ) {
        public CreateMerchantAccountRequest {
            supportedPresentmentCurrencies = supportedPresentmentCurrencies == null
                    ? Set.of()
                    : Set.copyOf(supportedPresentmentCurrencies);
        }
    }

    /** Enterprise, network, and gateway binding are immutable after creation to keep settlement scope auditable. */
    public record UpdateMerchantAccountRequest(
            @NotNull @Positive Integer expectedConfigurationVersion,
            @NotBlank @Size(max = 160) String legalEntityReference,
            @NotBlank @Size(max = 160) String providerMerchantReference,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String merchantCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String settlementCurrency,
            Set<@Pattern(regexp = "^[A-Za-z]{3}$") String> supportedPresentmentCurrencies
    ) {
        public UpdateMerchantAccountRequest {
            supportedPresentmentCurrencies = supportedPresentmentCurrencies == null
                    ? Set.of()
                    : Set.copyOf(supportedPresentmentCurrencies);
        }
    }

    public record CreatePaymentRouteRequest(
            @NotNull UUID merchantAccountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String chargingCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String presentmentCurrency,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String settlementCurrency,
            @NotNull PaymentChannel channel,
            @NotNull PaymentMethodType paymentMethod,
            int priority,
            boolean enabled,
            Set<GatewayCapability> requiredCapabilities,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        public CreatePaymentRouteRequest {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    /** Routes must be disabled before editing, and are explicitly enabled in a separate action. */
    public record UpdatePaymentRouteRequest(
            @NotNull @Positive Integer expectedConfigurationVersion,
            @NotNull UUID merchantAccountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String chargingCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String presentmentCurrency,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String settlementCurrency,
            @NotNull PaymentChannel channel,
            @NotNull PaymentMethodType paymentMethod,
            int priority,
            Set<GatewayCapability> requiredCapabilities,
            Instant effectiveFrom,
            Instant effectiveTo
    ) {
        public UpdatePaymentRouteRequest {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    /** Safe system-administration read model; raw secret references never appear here. */
    public record GatewayConfigurationSnapshot(
            java.util.List<GatewayConnection> connections,
            java.util.List<MerchantAccount> merchantAccounts,
            java.util.List<PaymentRoute> paymentRoutes
    ) {
        public GatewayConfigurationSnapshot {
            connections = connections == null ? java.util.List.of() : java.util.List.copyOf(connections);
            merchantAccounts = merchantAccounts == null ? java.util.List.of() : java.util.List.copyOf(merchantAccounts);
            paymentRoutes = paymentRoutes == null ? java.util.List.of() : java.util.List.copyOf(paymentRoutes);
        }
    }

    public record RouteResolutionRequest(
            @NotNull UUID merchantAccountId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String chargingCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String presentmentCurrency,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String settlementCurrency,
            @NotNull PaymentChannel channel,
            @NotNull PaymentMethodType paymentMethod,
            Set<GatewayCapability> requiredCapabilities
    ) {
        public RouteResolutionRequest {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    /**
     * Resolves a route from the immutable charger ownership snapshot persisted with a session.
     * Callers may not choose a merchant account directly because that would allow a tenant to
     * direct a charge to another network's settlement account.
     */
    public record ScopedRouteResolutionRequest(
            @NotBlank @Size(max = 80) String enterpriseId,
            @NotBlank @Size(max = 80) String networkId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String chargingCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String presentmentCurrency,
            @NotNull PaymentChannel channel,
            @NotNull PaymentMethodType paymentMethod,
            Set<GatewayCapability> requiredCapabilities
    ) {
        public ScopedRouteResolutionRequest {
            requiredCapabilities = requiredCapabilities == null ? Set.of() : Set.copyOf(requiredCapabilities);
        }
    }

    public record RouteResolution(
            boolean approved,
            String code,
            String message,
            UUID routeId,
            UUID connectionId,
            GatewayProvider provider,
            GatewayEnvironment environment,
            int configurationVersion,
            Set<GatewayCapability> capabilities
    ) {
        public RouteResolution {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }

        public static RouteResolution rejected(String code, String message) {
            return new RouteResolution(false, code, message, null, null, null, null, 0, Set.of());
        }
    }

    public record GatewayOperationRequest(
            @NotNull UUID routeId,
            @NotBlank @Size(max = 96) String paymentIntentId,
            @Size(max = 96) String paymentAttemptId,
            @NotBlank @Size(max = 96) String operationId,
            @NotBlank @Size(max = 128) String idempotencyKey,
            @NotNull GatewayOperationType operationType,
            @NotNull @DecimalMin(value = "0.00", inclusive = true) BigDecimal amount,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @Size(max = 128) String accountReference,
            @Size(max = 512) String paymentMethodReference,
            @Size(max = 255) String providerCustomerReference,
            @Size(max = 255) String providerReference,
            @Size(max = 512) String returnUrl,
            Instant requestedAt
    ) {
    }

    public record RegisterGatewayPaymentMethodRequest(
            @NotNull UUID connectionId,
            @NotBlank @Size(max = 128) String accountReference,
            @NotBlank @Size(max = 512) String providerToken,
            @NotBlank @Size(max = 30) String brand,
            @NotBlank @Pattern(regexp = "^\\d{4}$") String last4,
            @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(12) Integer expiryMonth,
            @NotNull @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(99) Integer expiryYear
    ) {
    }

    public record GatewayPaymentMethodRegistration(
            UUID id,
            UUID connectionId,
            GatewayProvider provider,
            String brand,
            String last4,
            int expiryMonth,
            int expiryYear,
            boolean active,
            Instant createdAt
    ) {
    }

    public record CreateGatewayPaymentMethodEnrollmentRequest(
            @NotBlank @Size(max = 80) String enterpriseId,
            @NotBlank @Size(max = 80) String networkId,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{2}$") String chargingCountry,
            @NotBlank @Pattern(regexp = "^[A-Za-z]{3}$") String currency,
            @NotBlank @Size(max = 128) String accountReference,
            @NotBlank @Size(max = 512) String returnUrl
    ) {
    }

    public record CompleteGatewayPaymentMethodEnrollmentRequest(
            @NotBlank @Size(max = 128) String accountReference
    ) {
    }

    public record GatewayPaymentMethodEnrollment(
            UUID id,
            UUID routeId,
            UUID connectionId,
            GatewayProvider provider,
            GatewayEnvironment environment,
            PaymentMethodEnrollmentStatus status,
            String clientSecret,
            String publishableKey,
            String currency,
            Instant expiresAt
    ) {
    }

    public record GatewayPaymentMethodEnrollmentCompletion(
            UUID enrollmentId,
            UUID routeId,
            String networkId,
            PaymentMethodEnrollmentStatus status,
            GatewayPaymentMethodRegistration paymentMethod
    ) {
    }
    /**
     * Provider-neutral customer action. The client secret is an ephemeral, customer-scoped
     * continuation token; provider account credentials are never returned here.
     */
    public record GatewayAction(
            @NotNull GatewayActionType type,
            String url,
            String clientSecret,
            Instant expiresAt,
            Map<String, String> data
    ) {
        public GatewayAction {
            data = data == null ? Map.of() : Map.copyOf(data);
        }
    }

    public record GatewayOperationResult(
            UUID gatewayOperationId,
            GatewayOperationStatus status,
            String code,
            String providerReference,
            String publicTransactionReference,
            GatewayAction action,
            Instant processedAt
    ) {
    }

    public record GatewayOperationStatusQuery(
            UUID gatewayOperationId,
            String operationId,
            String idempotencyKey,
            String providerReference,
            GatewayOperationType operationType,
            String publicTransactionReference
    ) {
        public GatewayOperationStatusQuery(
                UUID gatewayOperationId,
                String operationId,
                String idempotencyKey,
                String providerReference
        ) {
            this(gatewayOperationId, operationId, idempotencyKey, providerReference, null, null);
        }
    }

    /** Normalized only after the provider signature has been verified against the raw body. */
    public record GatewayWebhookEvent(
            @NotBlank @Size(max = 160) String providerEventId,
            @NotBlank @Size(max = 96) String eventType,
            @Size(max = 160) String providerReference,
            @Size(max = 160) String merchantReference,
            @Size(max = 160) String publicTransactionReference,
            @NotNull GatewayWebhookOutcome outcome,
            @NotBlank @Size(max = 96) String code,
            Instant occurredAt
    ) {
    }

    public record GatewayWebhookReceipt(
            UUID webhookEventId,
            boolean duplicate,
            boolean operationUpdated,
            String code,
            Instant receivedAt
    ) {
    }

    public record GatewayWebhookBatchReceipt(
            List<GatewayWebhookReceipt> events,
            int applied,
            int duplicates,
            Instant receivedAt
    ) {
        public GatewayWebhookBatchReceipt {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    public record ConnectionValidation(
            boolean valid,
            String code,
            String message,
            Set<GatewayCapability> capabilities,
            String adapterVersion
    ) {
        public ConnectionValidation {
            capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        }
    }
}
