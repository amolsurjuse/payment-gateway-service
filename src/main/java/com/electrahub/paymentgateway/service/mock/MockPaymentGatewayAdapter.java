package com.electrahub.paymentgateway.service.mock;

import com.electrahub.paymentgateway.config.GatewayProperties;
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
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
public class MockPaymentGatewayAdapter implements PaymentGatewayAdapter {

    private static final Set<GatewayCapability> CAPABILITIES = Set.of(
            GatewayCapability.AUTHORIZE,
            GatewayCapability.MANUAL_CAPTURE,
            GatewayCapability.CAPTURE,
            GatewayCapability.PARTIAL_CAPTURE,
            GatewayCapability.VOID,
            GatewayCapability.REFUND,
            GatewayCapability.STATUS_QUERY,
            GatewayCapability.THREE_DS_SCA,
            GatewayCapability.SETTLEMENT_REPORTS,
            GatewayCapability.PAYOUT_REPORTS
    );

    private final GatewayProperties properties;

    public MockPaymentGatewayAdapter(GatewayProperties properties) {
        this.properties = properties;
    }

    @Override
    public GatewayProvider provider() {
        return GatewayProvider.MOCK;
    }

    @Override
    public boolean requiresCredential(GatewayConnection connection) {
        return false;
    }

    @Override
    public ConnectionValidation validate(GatewayConnection connection) {
        if (!properties.mockProviderEnabled()) {
            return new ConnectionValidation(false, "MOCK_PROVIDER_DISABLED", "The mock payment adapter is disabled.", Set.of(), "mock-v1");
        }
        if (connection.environment() == GatewayEnvironment.PRODUCTION || properties.productionEnabled()) {
            return new ConnectionValidation(false, "MOCK_NOT_PERMITTED_PRODUCTION", "The mock provider cannot be activated for production money movement.", Set.of(), "mock-v1");
        }
        return new ConnectionValidation(true, "READY", "Mock sandbox connection validated.", CAPABILITIES, "mock-v1");
    }

    @Override
    public GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection) {
        requireEnabled(connection);
        String reference = request.paymentMethodReference() == null ? "" : request.paymentMethodReference().trim().toLowerCase();
        if (reference.contains("mock:timeout")) {
            throw new GatewayUnavailableException("PAYMENT_PROVIDER_UNAVAILABLE", "Mock provider timeout scenario requested.");
        }
        if (reference.contains("mock:decline")) {
            return result(request, GatewayOperationStatus.DECLINED, "PAYMENT_AUTHORIZATION_DECLINED", null);
        }
        if (reference.contains("mock:action-required")) {
            return new GatewayOperationResult(
                    UUID.randomUUID(),
                    GatewayOperationStatus.ACTION_REQUIRED,
                    "PAYMENT_CUSTOMER_ACTION_REQUIRED",
                    "mock-" + request.operationId(),
                    "EHP-" + request.operationId(),
                    new GatewayAction(
                            GatewayActionType.REDIRECT,
                            "https://example.invalid/mock-payment-action",
                            null,
                            Instant.now().plus(30, ChronoUnit.MINUTES),
                            Map.of()
                    ),
                    Instant.now()
            );
        }
        return result(request, GatewayOperationStatus.SUCCEEDED, "APPROVED", "mock-" + request.operationId());
    }

    @Override
    public GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection) {
        requireEnabled(connection);
        // A timeout deliberately stays unresolved in the mock until a test injects a terminal provider webhook.
        return new GatewayOperationResult(
                query.gatewayOperationId(),
                GatewayOperationStatus.PENDING_RECONCILIATION,
                "PROVIDER_STATUS_PENDING",
                query.providerReference(),
                null,
                null,
                Instant.now()
        );
    }

    private void requireEnabled(GatewayConnection connection) {
        if (!properties.mockProviderEnabled()
                || properties.productionEnabled()
                || connection.environment() == GatewayEnvironment.PRODUCTION) {
            throw new GatewayBusinessException(
                    "MOCK_PROVIDER_DISABLED",
                    "Mock payment execution is disabled outside an explicitly enabled sandbox."
            );
        }
    }

    private GatewayOperationResult result(
            GatewayOperationRequest request,
            GatewayOperationStatus status,
            String code,
            String providerReference
    ) {
        return new GatewayOperationResult(
                UUID.randomUUID(),
                status,
                code,
                providerReference,
                "EHP-" + request.operationId(),
                null,
                Instant.now()
        );
    }
}
