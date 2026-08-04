package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookEvent;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayWebhookVerificationException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GatewayWebhookServiceTest {

    @Test
    void treatsAnAutomaticRefundAsConfirmationOfAVoid() {
        assertThat(GatewayWebhookService.targetStatus("VOID", GatewayWebhookOutcome.REFUNDED))
                .isEqualTo(GatewayOperationStatus.SUCCEEDED);
    }

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

    @Test
    void scopesIdempotencyCorrelationThroughTheMerchantConnection() {
        assertCorrelationUsesMerchantConnection(new GatewayWebhookEvent(
                "evt-idempotency", "payment_intent.succeeded", "pi-1", null, null,
                GatewayWebhookOutcome.CAPTURED, "CAPTURED", Instant.now(),
                new BigDecimal("10.00"), "USD", "capture-idem-1"
        ));
    }

    @Test
    void scopesPublicReferenceCorrelationThroughTheMerchantConnection() {
        assertCorrelationUsesMerchantConnection(new GatewayWebhookEvent(
                "evt-public-reference", "payment_intent.succeeded", null, null, "ch-1",
                GatewayWebhookOutcome.CAPTURED, "CAPTURED", Instant.now(),
                new BigDecimal("10.00"), "USD", null
        ));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void durablyRecordsUnmatchedStripePaymentIntentWebhookAsIgnored() {
        Fixture fixture = fixture();
        GatewayConnection connection = connection();
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                "evt-stripe-unmatched", "payment_intent.succeeded", "pi_unmatched",
                null, null, GatewayWebhookOutcome.CAPTURED, "CAPTURED", Instant.now()
        );
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.STRIPE)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "signed-body", Map.of())).thenReturn(List.of(event));
        executeTransactions(fixture);
        AtomicReference<String> correlationSql = new AtomicReference<>();
        AtomicReference<String> insertSql = new AtomicReference<>();
        AtomicReference<String> persistedStatus = new AtomicReference<>();
        doAnswer(invocation -> {
            correlationSql.set(invocation.getArgument(0));
            return List.of();
        }).when(fixture.jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            if (sql.contains("INSERT INTO payment_gateway.gateway_webhook_event")) {
                insertSql.set(sql);
            } else if (sql.contains("SET gateway_operation_id = ?")) {
                persistedStatus.set(invocation.getArgument(2));
            }
            return 1;
        }).when(fixture.jdbcTemplate).update(anyString(), any(Object[].class));

        var receipt = fixture.service.receive(connection.id(), "signed-body", Map.of());

        assertThat(receipt.applied()).isZero();
        assertThat(receipt.duplicates()).isZero();
        assertThat(receipt.events()).singleElement().satisfies(webhookReceipt -> {
            assertThat(webhookReceipt.operationUpdated()).isFalse();
            assertThat(webhookReceipt.code()).isEqualTo("GATEWAY_WEBHOOK_NO_MATCH");
        });
        assertThat(correlationSql.get())
                .contains("JOIN payment_gateway.merchant_payment_account merchant")
                .contains("merchant.connection_id = ?")
                .contains("operation.provider_reference = ?")
                .doesNotContain("route.connection_id");
        assertThat(insertSql.get()).contains("INSERT INTO payment_gateway.gateway_webhook_event");
        assertThat(persistedStatus.get()).isEqualTo("IGNORED");
    }

    @Test
    void rejectsSignedWebhookAmountMismatchBeforeDurableIngestion() {
        assertStableFieldMismatch(
                new GatewayWebhookEvent(
                        "2c2p-refund-1", "REFUND", "invoice-1", "invoice-1", "refund-1",
                        GatewayWebhookOutcome.REFUNDED, "RF", Instant.now(),
                        new BigDecimal("10.01"), "USD", "refund-idem-1"
                ),
                "GATEWAY_WEBHOOK_AMOUNT_MISMATCH"
        );
    }

    @Test
    void rejectsSignedWebhookCurrencyMismatchBeforeDurableIngestion() {
        assertStableFieldMismatch(
                new GatewayWebhookEvent(
                        "2c2p-refund-1", "REFUND", "invoice-1", "invoice-1", "refund-1",
                        GatewayWebhookOutcome.REFUNDED, "RF", Instant.now(),
                        new BigDecimal("10.00"), "EUR", "refund-idem-1"
                ),
                "GATEWAY_WEBHOOK_CURRENCY_MISMATCH"
        );
    }

    @Test
    void rejectsSignedWebhookIdempotencyMismatchBeforeDurableIngestion() {
        assertStableFieldMismatch(
                new GatewayWebhookEvent(
                        "2c2p-refund-1", "REFUND", "invoice-1", "invoice-1", "refund-1",
                        GatewayWebhookOutcome.REFUNDED, "RF", Instant.now(),
                        new BigDecimal("10.00"), "USD", "different-refund-idem"
                ),
                "GATEWAY_WEBHOOK_IDEMPOTENCY_MISMATCH"
        );
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void correlatesRefundWithoutOptionalIdempotencyByExactStableFields() {
        Fixture fixture = fixture();
        GatewayConnection connection = connection(GatewayProvider.TWO_C2P);
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                "2c2p-refund-no-idem", "payment.maintenance.refund",
                "invoice-1", "invoice-1", "refund-1",
                GatewayWebhookOutcome.REFUNDED, "REFUNDED", Instant.now(),
                new BigDecimal("10.00"), "USD", null
        );
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.TWO_C2P)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "signed-body", Map.of())).thenReturn(List.of(event));
        executeTransactions(fixture);
        AtomicReference<String> correlationSql = new AtomicReference<>();
        AtomicReference<Object[]> correlationArguments = new AtomicReference<>();
        doAnswer(invocation -> {
            String sql = invocation.getArgument(0);
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            if (sql.contains("operation.operation_type = 'REFUND'")) {
                correlationSql.set(sql);
                correlationArguments.set(java.util.Arrays.copyOfRange(
                        invocation.getArguments(), 2, invocation.getArguments().length
                ));
                stubOperationMatch(resultSet, UUID.randomUUID(), "10.00", "USD", "refund-idem-1");
            } else {
                when(resultSet.getString("operation_type")).thenReturn("REFUND");
                when(resultSet.getString("status")).thenReturn("PENDING_RECONCILIATION");
            }
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(fixture.jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(fixture.jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        var receipt = fixture.service.receive(connection.id(), "signed-body", Map.of());

        assertThat(receipt.applied()).isEqualTo(1);
        assertThat(correlationSql.get())
                .contains("JOIN payment_gateway.merchant_payment_account merchant")
                .contains("merchant.id = route.merchant_account_id")
                .contains("merchant.connection_id = ?")
                .doesNotContain("route.connection_id")
                .contains("operation.operation_type = 'REFUND'")
                .contains("operation.provider_reference = ?")
                .contains("operation.public_transaction_reference = ?")
                .contains("operation.amount = ?")
                .contains("operation.currency = ?")
                .contains("LIMIT 2");
        assertThat(correlationArguments.get()).contains(
                "invoice-1", "refund-1", new BigDecimal("10.00"), "USD"
        );
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void rejectsAmbiguousConcurrentRefundCandidatesBeforeIngestion() {
        Fixture fixture = fixture();
        GatewayConnection connection = connection(GatewayProvider.TWO_C2P);
        GatewayWebhookEvent event = new GatewayWebhookEvent(
                "2c2p-refund-ambiguous", "payment.maintenance.refund",
                "invoice-1", "invoice-1", "refund-1",
                GatewayWebhookOutcome.REFUNDED, "REFUNDED", Instant.now(),
                new BigDecimal("10.00"), "USD", null
        );
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.TWO_C2P)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "signed-body", Map.of())).thenReturn(List.of(event));
        executeTransactions(fixture);
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet first = mock(ResultSet.class);
            ResultSet second = mock(ResultSet.class);
            stubOperationMatch(first, UUID.randomUUID(), "10.00", "USD", "refund-idem-1");
            stubOperationMatch(second, UUID.randomUUID(), "10.00", "USD", "refund-idem-2");
            return List.of(mapper.mapRow(first, 0), mapper.mapRow(second, 1));
        }).when(fixture.jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> fixture.service.receive(connection.id(), "signed-body", Map.of()))
                .isInstanceOfSatisfying(GatewayWebhookVerificationException.class, exception ->
                        assertThat(exception.code()).isEqualTo("GATEWAY_WEBHOOK_OPERATION_AMBIGUOUS")
                );

        verify(fixture.jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertCorrelationUsesMerchantConnection(GatewayWebhookEvent event) {
        Fixture fixture = fixture();
        GatewayConnection connection = connection();
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.STRIPE)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "signed-body", Map.of())).thenReturn(List.of(event));
        executeTransactions(fixture);
        AtomicReference<String> correlationSql = new AtomicReference<>();
        doAnswer(invocation -> {
            correlationSql.set(invocation.getArgument(0));
            return List.of();
        }).when(fixture.jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));
        when(fixture.jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        fixture.service.receive(connection.id(), "signed-body", Map.of());

        assertThat(correlationSql.get())
                .contains("JOIN payment_gateway.payment_route route ON route.id = operation.route_id")
                .contains("JOIN payment_gateway.merchant_payment_account merchant")
                .contains("merchant.id = route.merchant_account_id")
                .contains("merchant.connection_id = ?")
                .doesNotContain("route.connection_id");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void assertStableFieldMismatch(GatewayWebhookEvent event, String expectedCode) {
        Fixture fixture = fixture();
        GatewayConnection connection = connection(GatewayProvider.TWO_C2P);
        when(fixture.configurationService.requireConnection(connection.id())).thenReturn(connection);
        when(fixture.registry.find(GatewayProvider.TWO_C2P)).thenReturn(Optional.of(fixture.adapter));
        when(fixture.adapter.parseWebhooks(connection, "signed-body", Map.of())).thenReturn(List.of(event));
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(fixture.transactionTemplate).execute(any(TransactionCallback.class));
        doAnswer(invocation -> {
            RowMapper<?> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getObject("id", UUID.class)).thenReturn(UUID.randomUUID());
            when(resultSet.getBigDecimal("amount")).thenReturn(new BigDecimal("10.00"));
            when(resultSet.getString("currency")).thenReturn("USD");
            when(resultSet.getString("idempotency_key")).thenReturn("refund-idem-1");
            return List.of(mapper.mapRow(resultSet, 0));
        }).when(fixture.jdbcTemplate).query(anyString(), any(RowMapper.class), any(Object[].class));

        assertThatThrownBy(() -> fixture.service.receive(connection.id(), "signed-body", Map.of()))
                .isInstanceOfSatisfying(GatewayWebhookVerificationException.class, exception ->
                        assertThat(exception.code()).isEqualTo(expectedCode)
                );

        verify(fixture.jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void executeTransactions(Fixture fixture) {
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(fixture.transactionTemplate).execute(any(TransactionCallback.class));
    }

    private void stubOperationMatch(
            ResultSet resultSet,
            UUID id,
            String amount,
            String currency,
            String idempotencyKey
    ) throws Exception {
        when(resultSet.getObject("id", UUID.class)).thenReturn(id);
        when(resultSet.getBigDecimal("amount")).thenReturn(new BigDecimal(amount));
        when(resultSet.getString("currency")).thenReturn(currency);
        when(resultSet.getString("idempotency_key")).thenReturn(idempotencyKey);
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
        return connection(GatewayProvider.STRIPE);
    }

    private GatewayConnection connection(GatewayProvider provider) {
        Instant now = Instant.now();
        return new GatewayConnection(
                UUID.randomUUID(), provider, GatewayEnvironment.SANDBOX,
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
