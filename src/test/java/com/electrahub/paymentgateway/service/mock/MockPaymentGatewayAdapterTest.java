package com.electrahub.paymentgateway.service.mock;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MockPaymentGatewayAdapterTest {

    private final MockPaymentGatewayAdapter adapter = new MockPaymentGatewayAdapter(
            new GatewayProperties(Duration.ofMinutes(10), true, false)
    );

    @Test
    void validatesOnlySandboxMockConnections() {
        assertThat(adapter.validate(connection(GatewayEnvironment.SANDBOX)).valid()).isTrue();
        assertThat(adapter.validate(connection(GatewayEnvironment.PRODUCTION)).code())
                .isEqualTo("MOCK_NOT_PERMITTED_PRODUCTION");
    }

    @Test
    void exposesDeterministicApprovalDeclineActionAndTimeoutScenarios() {
        assertThat(adapter.execute(request("token_mock:approve"), connection(GatewayEnvironment.SANDBOX)).status())
                .isEqualTo(GatewayOperationStatus.SUCCEEDED);
        assertThat(adapter.execute(request("token_mock:decline"), connection(GatewayEnvironment.SANDBOX)).status())
                .isEqualTo(GatewayOperationStatus.DECLINED);
        assertThat(adapter.execute(request("token_mock:action-required"), connection(GatewayEnvironment.SANDBOX)).status())
                .isEqualTo(GatewayOperationStatus.ACTION_REQUIRED);
        assertThatThrownBy(() -> adapter.execute(request("token_mock:timeout"), connection(GatewayEnvironment.SANDBOX)))
                .isInstanceOf(GatewayUnavailableException.class);
    }

    @Test
    void keepsTimeoutInquiryPendingRatherThanReplayingACharge() {
        assertThat(adapter.queryStatus(
                new GatewayOperationStatusQuery(UUID.randomUUID(), "op_timeout", "idem_timeout", null),
                connection(GatewayEnvironment.SANDBOX)
        ).status()).isEqualTo(GatewayOperationStatus.PENDING_RECONCILIATION);
    }

    private GatewayConnection connection(GatewayEnvironment environment) {
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.MOCK, environment, GatewayConnectionStatus.READY,
                "mock-v1", "sandbox", Set.of(), false, false, false,
                null, null, null, 1, Instant.now(), Instant.now()
        );
    }

    private GatewayOperationRequest request(String reference) {
        return new GatewayOperationRequest(
                UUID.randomUUID(), "pi_123", null, "op_123", "idem_123", GatewayOperationType.AUTHORIZE,
                new BigDecimal("25.00"), "USD", reference, Instant.now()
        );
    }
}
