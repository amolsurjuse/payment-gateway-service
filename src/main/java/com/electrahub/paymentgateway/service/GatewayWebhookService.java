package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookBatchReceipt;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookEvent;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookOutcome;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookReceipt;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.HashSet;
import java.util.UUID;

@Service
public class GatewayWebhookService {

    static final int MAX_WEBHOOK_BYTES = 256 * 1024;

    private final JdbcTemplate jdbcTemplate;
    private final GatewayConfigurationService configurationService;
    private final PaymentGatewayRegistry adapterRegistry;
    private final TransactionTemplate transactionTemplate;

    public GatewayWebhookService(
            JdbcTemplate jdbcTemplate,
            GatewayConfigurationService configurationService,
            PaymentGatewayRegistry adapterRegistry,
            TransactionTemplate transactionTemplate
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.configurationService = configurationService;
        this.adapterRegistry = adapterRegistry;
        this.transactionTemplate = transactionTemplate;
    }

    public GatewayWebhookBatchReceipt receive(UUID connectionId, String rawBody, Map<String, String> headers) {
        String body = rawBody == null ? "" : rawBody;
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_WEBHOOK_BYTES) {
            throw new GatewayBusinessException("GATEWAY_WEBHOOK_TOO_LARGE", "The webhook payload is too large.");
        }
        GatewayConnection connection = configurationService.requireConnection(connectionId);
        PaymentGatewayAdapter adapter = adapterRegistry.find(connection.provider())
                .orElseThrow(() -> new GatewayBusinessException("ADAPTER_NOT_INSTALLED", "The payment provider adapter is not installed."));

        // Provider verification must use the exact body bytes and happens before durable ingestion.
        List<GatewayWebhookEvent> events = adapter.parseWebhooks(connection, body, headers == null ? Map.of() : headers);
        if (events.isEmpty() || events.size() > 100) {
            throw new GatewayBusinessException("GATEWAY_WEBHOOK_BATCH_INVALID", "The webhook event batch is empty or too large.");
        }
        HashSet<String> eventIds = new HashSet<>();
        if (events.stream().anyMatch(event -> !eventIds.add(event.providerEventId()))) {
            throw new GatewayBusinessException("GATEWAY_WEBHOOK_BATCH_DUPLICATE", "The webhook batch contains duplicate event identifiers.");
        }
        Instant receivedAt = Instant.now();
        return transactionTemplate.execute(status -> {
            List<GatewayWebhookReceipt> receipts = events.stream()
                    .map(event -> persist(connection, event, sha256(body), receivedAt))
                    .toList();
            int applied = (int) receipts.stream().filter(GatewayWebhookReceipt::operationUpdated).count();
            int duplicates = (int) receipts.stream().filter(GatewayWebhookReceipt::duplicate).count();
            return new GatewayWebhookBatchReceipt(receipts, applied, duplicates, receivedAt);
        });
    }

    private GatewayWebhookReceipt persist(
            GatewayConnection connection,
            GatewayWebhookEvent event,
            String payloadHash,
            Instant receivedAt
    ) {
        UUID eventId = UUID.randomUUID();
        int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO payment_gateway.gateway_webhook_event
                        (id, connection_id, provider_event_id, event_type, provider_reference, merchant_reference,
                         public_transaction_reference, outcome, event_code, payload_sha256, processing_status,
                         occurred_at, received_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'VERIFIED', ?, ?)
                    ON CONFLICT (connection_id, provider_event_id) DO NOTHING
                    """,
                    eventId,
                    connection.id(),
                    event.providerEventId(),
                    event.eventType(),
                    blankToNull(event.providerReference()),
                    blankToNull(event.merchantReference()),
                    blankToNull(event.publicTransactionReference()),
                    event.outcome().name(),
                    event.code(),
                    payloadHash,
                    event.occurredAt() == null ? null : offset(event.occurredAt()),
                    offset(receivedAt)
            );
        if (inserted == 0) {
            GatewayWebhookReceipt duplicate = findDuplicate(connection.id(), event.providerEventId());
            if (duplicate != null) {
                return duplicate;
            }
            throw new IllegalStateException("Webhook conflict was not readable after insert.");
        }

        UUID operationId = findOperation(connection.id(), event);
        boolean operationUpdated = operationId != null && applyOutcome(operationId, event);
        String processingStatus = operationUpdated ? "APPLIED" : "IGNORED";
        String receiptCode = operationUpdated ? "GATEWAY_WEBHOOK_APPLIED" : "GATEWAY_WEBHOOK_NO_MATCH";
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_webhook_event
                   SET gateway_operation_id = ?, processing_status = ?, processed_at = ?
                 WHERE id = ?
                """,
                operationId, processingStatus, offset(Instant.now()), eventId
        );
        return new GatewayWebhookReceipt(eventId, false, operationUpdated, receiptCode, receivedAt);
    }

    private UUID findOperation(UUID connectionId, GatewayWebhookEvent event) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT operation.id
                  FROM payment_gateway.gateway_operation operation
                  JOIN payment_gateway.payment_route route ON route.id = operation.route_id
                 WHERE route.connection_id = ?
                   AND operation.status IN ('PENDING_RECONCILIATION', 'ACTION_REQUIRED')
                   AND ((? IS NOT NULL AND operation.provider_reference = ?)
                     OR (? IS NOT NULL AND operation.public_transaction_reference = ?)
                     OR (? IS NOT NULL AND operation.payment_intent_id = ?)
                     OR (? IS NOT NULL AND operation.operation_id = ?)
                     OR (? IS NOT NULL AND operation.provider_reference = ?)
                     OR (? IS NOT NULL AND operation.public_transaction_reference = ?))
                 ORDER BY operation.created_at DESC
                 LIMIT 1
                """,
                (rs, rowNum) -> rs.getObject("id", UUID.class),
                connectionId,
                blankToNull(event.providerReference()), blankToNull(event.providerReference()),
                blankToNull(event.merchantReference()), blankToNull(event.merchantReference()),
                blankToNull(event.merchantReference()), blankToNull(event.merchantReference()),
                blankToNull(event.merchantReference()), blankToNull(event.merchantReference()),
                blankToNull(event.publicTransactionReference()), blankToNull(event.publicTransactionReference()),
                blankToNull(event.publicTransactionReference()), blankToNull(event.publicTransactionReference())
        ));
    }

    private boolean applyOutcome(UUID operationId, GatewayWebhookEvent event) {
        OperationRow operation = DataAccessUtils.singleResult(jdbcTemplate.query(
                "SELECT operation_type, status FROM payment_gateway.gateway_operation WHERE id = ? FOR UPDATE",
                this::mapOperation,
                operationId
        ));
        if (operation == null || isTerminal(operation.status())) {
            return false;
        }

        GatewayOperationStatus target = targetStatus(operation.operationType(), event.outcome());
        if (target == GatewayOperationStatus.PENDING_RECONCILIATION) {
            return false;
        }
        int updated = jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_operation
                   SET status = ?, error_code = ?,
                       provider_reference = COALESCE(provider_reference, ?),
                       public_transaction_reference = COALESCE(?, public_transaction_reference),
                       action_type = NULL, action_url = NULL, action_client_secret = NULL,
                       action_expires_at = NULL, action_data = NULL, updated_at = ?
                 WHERE id = ?
                   AND status IN ('PENDING_RECONCILIATION', 'ACTION_REQUIRED')
                """,
                target.name(),
                target == GatewayOperationStatus.SUCCEEDED ? null : event.code(),
                blankToNull(event.providerReference()),
                blankToNull(event.publicTransactionReference()),
                offset(Instant.now()),
                operationId
        );
        return updated == 1;
    }

    private GatewayOperationStatus targetStatus(String operationType, GatewayWebhookOutcome outcome) {
        return switch (outcome) {
            case AUTHORIZED -> "AUTHORIZE".equals(operationType)
                    ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.PENDING_RECONCILIATION;
            case CAPTURED -> ("CAPTURE".equals(operationType) || "AUTHORIZE".equals(operationType))
                    ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.PENDING_RECONCILIATION;
            case VOIDED -> "VOID".equals(operationType)
                    ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.FAILED;
            case REFUNDED -> "REFUND".equals(operationType)
                    ? GatewayOperationStatus.SUCCEEDED : GatewayOperationStatus.PENDING_RECONCILIATION;
            case DECLINED -> GatewayOperationStatus.DECLINED;
            case ACTION_REQUIRED -> GatewayOperationStatus.ACTION_REQUIRED;
            case PENDING -> GatewayOperationStatus.PENDING_RECONCILIATION;
        };
    }

    private GatewayWebhookReceipt findDuplicate(UUID connectionId, String providerEventId) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, gateway_operation_id, processing_status, received_at
                  FROM payment_gateway.gateway_webhook_event
                 WHERE connection_id = ? AND provider_event_id = ?
                """,
                (rs, rowNum) -> new GatewayWebhookReceipt(
                        rs.getObject("id", UUID.class),
                        true,
                        rs.getObject("gateway_operation_id") != null,
                        "GATEWAY_WEBHOOK_DUPLICATE",
                        instant(rs, "received_at")
                ),
                connectionId, providerEventId
        ));
    }

    private OperationRow mapOperation(ResultSet rs, int rowNum) throws SQLException {
        return new OperationRow(rs.getString("operation_type"), rs.getString("status"));
    }

    private boolean isTerminal(String status) {
        return "SUCCEEDED".equals(status) || "DECLINED".equals(status) || "FAILED".equals(status);
    }

    private String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record OperationRow(String operationType, String status) {
    }
}
