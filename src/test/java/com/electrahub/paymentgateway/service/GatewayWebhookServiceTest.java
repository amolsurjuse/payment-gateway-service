package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookEvent;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayWebhookServiceTest {

    @Test
    void rejectsOversizedPayloadBeforeLookingUpAConnection() {
        Fixture fixture = fixture();
        String payload = "x".repeat(GatewayWebhookService.MAX_WEBHOOK_BYTES + 1);

        assertThatThrownBy(() -> fixture.service.receive(UUID.randomUUID(), payload, Map.of()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("too large");

        verifyNoInteractions(fixture.configurationService, fixture.jdbcTemplate, fixture.transactionTemplate);
    }

    @Test
    void neverStartsDatabaseIngestionWhenProviderVerificationFails() {
        Fixture fixture = fixture();
        GatewayConnection connection = connection();
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.STRIPE)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "{}", Map.of("stripe-signature", "bad")))
                .thenThrow(new GatewayWebhookVerificationException("STRIPE_WEBHOOK_SIGNATURE_INVALID", "Invalid signature."));

        assertThatThrownBy(() -> fixture.service.receive(
                connection.id(), "{}", Map.of("stripe-signature", "bad")))
                .isInstanceOf(GatewayWebhookVerificationException.class);

        verifyNoInteractions(fixture.jdbcTemplate, fixture.transactionTemplate);
    }

    @Test
    void rejectsDuplicateProviderEventIdsBeforeDatabaseIngestion() {
        Fixture fixture = fixture();
        GatewayConnection connection = connection();
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                "evt-1", "payment_intent.succeeded", "pi-1", null, null,
                GatewayWebhookOutcome.CAPTURED, "CAPTURED", Instant.now()
        );
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.STRIPE)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "{}", Map.of())).thenReturn(List.of(event, event));

        assertThatThrownBy(() -> fixture.service.receive(connection.id(), "{}", Map.of()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("duplicate event identifiers");

        verifyNoInteractions(fixture.jdbcTemplate, fixture.transactionTemplate);
    }

    private Fixture fixture() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GatewayConfigurationService configurationService = mock(GatewayConfigurationService.class);
        PaymentGatewayRegistry registry = mock(PaymentGatewayRegistry.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        PaymentGatewayAdapter adapter = mock(PaymentGatewayAdapter.class);
        GatewayWebhookService service = new GatewayWebhookService(
                jdbcTemplate, configurationService, registry, transactionTemplate
        );
        return new Fixture(service, jdbcTemplate, configurationService, registry, transactionTemplate, adapter);
    }

    private GatewayConnection connection() {
        Instant now = Instant.now();
        return new GatewayConnection(
                UUID.randomUUID(), GatewayProvider.STRIPE, GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.ACTIVE, "2026-07", "test", Set.of(),
                true, true, false, now, now, null, 1, now, now
        );
    }

    private record Fixture(
            GatewayWebhookService service,
            JdbcTemplate jdbcTemplate,
            GatewayConfigurationService configurationService,
            PaymentGatewayRegistry registry,
            TransactionTemplate transactionTemplate,
            PaymentGatewayAdapter adapter
    ) {
    }
}
