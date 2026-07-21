package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class GatewayOperationService {

    private final JdbcTemplate jdbcTemplate;
    private final GatewayConfigurationService configurationService;
    private final GatewayRoutePolicy routePolicy;
    private final PaymentGatewayRegistry adapterRegistry;
    private final GatewayOperationRecoveryPolicy recoveryPolicy;

    public GatewayOperationService(
            JdbcTemplate jdbcTemplate,
            GatewayConfigurationService configurationService,
            GatewayRoutePolicy routePolicy,
            PaymentGatewayRegistry adapterRegistry,
            GatewayOperationRecoveryPolicy recoveryPolicy
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.configurationService = configurationService;
        this.routePolicy = routePolicy;
        this.adapterRegistry = adapterRegistry;
        this.recoveryPolicy = recoveryPolicy;
    }

    /**
     * Creates a durable operation before invoking an adapter. Provider calls are outside
     * a database transaction, and a timeout becomes a recoverable inquiry state.
     */
    public GatewayOperationResult execute(GatewayOperationRequest request) {
        rejectRawPaymentData(request.paymentMethodReference());
        GatewayOperationResult existing = findByIdempotency(request.operationType().name(), request.idempotencyKey());
        if (existing != null) {
            return existing;
        }

        GatewayRouteCandidate candidate = configurationService.requireRouteCandidate(request.routeId());
        RouteResolution decision = routePolicy.evaluate(
                candidate.route(),
                candidate.connection(),
                new RouteResolutionRequest(
                        candidate.route().merchantAccountId(),
                        candidate.route().chargingCountry(),
                        candidate.route().presentmentCurrency(),
                        candidate.route().settlementCurrency(),
                        candidate.route().channel(),
                        candidate.route().paymentMethod(),
                        requiredCapability(request.operationType())
                ),
                Instant.now()
        );
        if (!decision.approved()) {
            throw new GatewayBusinessException(decision.code(), decision.message());
        }

        UUID gatewayOperationId = UUID.randomUUID();
        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO payment_gateway.gateway_operation
                        (id, route_id, provider_code, payment_intent_id, payment_attempt_id, operation_id,
                         operation_type, idempotency_key, amount, currency, status, error_code, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_RECONCILIATION', 'PENDING_RECONCILIATION', ?, ?)
                    """,
                    gatewayOperationId,
                    request.routeId(),
                    candidate.connection().provider().name(),
                    request.paymentIntentId().trim(),
                    blankToNull(request.paymentAttemptId()),
                    request.operationId().trim(),
                    request.operationType().name(),
                    request.idempotencyKey().trim(),
                    normalizeAmount(request.amount()),
                    request.currency().trim().toUpperCase(),
                    offset(Instant.now()),
                    offset(Instant.now())
            );
        } catch (DataIntegrityViolationException ex) {
            GatewayOperationResult concurrent = findByIdempotency(request.operationType().name(), request.idempotencyKey());
            if (concurrent != null) {
                return concurrent;
            }
            throw ex;
        }

        PaymentGatewayAdapter adapter = adapterRegistry.find(candidate.connection().provider())
                .orElseThrow(() -> new GatewayBusinessException("ADAPTER_NOT_INSTALLED", "The payment provider adapter is not installed."));
        try {
            GatewayOperationResult adapterResult = adapter.execute(request, candidate.connection());
            GatewayOperationResult persisted = new GatewayOperationResult(
                    gatewayOperationId,
                    adapterResult.status(),
                    adapterResult.code(),
                    adapterResult.providerReference(),
                    adapterResult.publicTransactionReference(),
                    adapterResult.processedAt()
            );
            persistResult(persisted);
            if (persisted.status() == GatewayOperationStatus.PENDING_RECONCILIATION) {
                scheduleRecovery(gatewayOperationId, persisted.code(), 0, Instant.now(), persisted.providerReference());
            }
            return persisted;
        } catch (GatewayUnavailableException ex) {
            GatewayOperationResult pending = new GatewayOperationResult(
                    gatewayOperationId,
                    GatewayOperationStatus.PENDING_RECONCILIATION,
                    ex.code(),
                    null,
                    null,
                    Instant.now()
            );
            persistResult(pending);
            scheduleRecovery(gatewayOperationId, pending.code(), 0, Instant.now(), null);
            return pending;
        } catch (GatewayBusinessException ex) {
            GatewayOperationStatus status = ex.code().contains("DECLINED")
                    ? GatewayOperationStatus.DECLINED
                    : GatewayOperationStatus.FAILED;
            GatewayOperationResult failed = new GatewayOperationResult(
                    gatewayOperationId, status, ex.code(), null, null, Instant.now());
            persistResult(failed);
            return failed;
        } catch (RuntimeException ex) {
            // Once an adapter call has started, an unclassified failure has an
            // uncertain provider outcome. Reconcile it rather than risk a
            // duplicate financial mutation or falsely report it as declined.
            GatewayOperationResult pending = new GatewayOperationResult(
                    gatewayOperationId,
                    GatewayOperationStatus.PENDING_RECONCILIATION,
                    "PAYMENT_PROVIDER_UNAVAILABLE",
                    null,
                    null,
                    Instant.now()
            );
            persistResult(pending);
            scheduleRecovery(gatewayOperationId, pending.code(), 0, Instant.now(), null);
            return pending;
        }
    }

    /**
     * Reconciles only through provider status inquiry. It never retries an authorize,
     * capture, void, or refund mutation without a provider-confirmed terminal result.
     */
    public int reconcilePendingOperations(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        Instant now = Instant.now();
        List<GatewayOperationRecoveryRecord> pending = claimPendingOperations(safeLimit, now);
        int finalized = 0;
        for (GatewayOperationRecoveryRecord operation : pending) {
            try {
                GatewayRouteCandidate candidate = configurationService.requireRouteCandidate(operation.routeId());
                PaymentGatewayAdapter adapter = adapterRegistry.find(candidate.connection().provider()).orElse(null);
                if (adapter == null) {
                    scheduleRecovery(operation.id(), "ADAPTER_NOT_INSTALLED", operation.recoveryAttemptCount(), Instant.now(), null);
                    continue;
                }
                GatewayOperationResult providerStatus = adapter.queryStatus(
                        new com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery(
                                operation.id(), operation.operationId(), operation.idempotencyKey(), operation.providerReference()),
                        candidate.connection()
                );
                if (providerStatus.status() == GatewayOperationStatus.PENDING_RECONCILIATION) {
                    scheduleRecovery(
                            operation.id(),
                            providerStatus.code(),
                            operation.recoveryAttemptCount(),
                            Instant.now(),
                            providerStatus.providerReference()
                    );
                    continue;
                }
                GatewayOperationResult finalizedResult = new GatewayOperationResult(
                        operation.id(),
                        providerStatus.status(),
                        providerStatus.code(),
                        providerStatus.providerReference(),
                        providerStatus.publicTransactionReference(),
                        providerStatus.processedAt()
                );
                persistResult(finalizedResult);
                finalized++;
            } catch (GatewayUnavailableException ex) {
                scheduleRecovery(operation.id(), ex.code(), operation.recoveryAttemptCount(), Instant.now(), null);
            } catch (GatewayBusinessException ex) {
                scheduleRecovery(operation.id(), ex.code(), operation.recoveryAttemptCount(), Instant.now(), null);
            } catch (RuntimeException ex) {
                scheduleRecovery(operation.id(), "RECOVERY_INQUIRY_FAILED", operation.recoveryAttemptCount(), Instant.now(), null);
            }
        }
        return finalized;
    }

    private void persistResult(GatewayOperationResult result) {
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_operation
                   SET status = ?, error_code = ?, provider_reference = ?, public_transaction_reference = ?,
                       recovery_lease_until = NULL, next_recovery_at = NULL, last_recovery_error_code = NULL, updated_at = ?
                 WHERE id = ?
                """,
                result.status().name(),
                result.status() == GatewayOperationStatus.SUCCEEDED && "APPROVED".equals(result.code())
                        ? null
                        : result.code(),
                result.providerReference(),
                result.publicTransactionReference(),
                offset(result.processedAt()),
                result.gatewayOperationId()
        );
    }

    private List<GatewayOperationRecoveryRecord> claimPendingOperations(int limit, Instant now) {
        Instant leaseUntil = recoveryPolicy.leaseUntil(now);
        return jdbcTemplate.query(
                """
                WITH due AS (
                    SELECT id
                      FROM payment_gateway.gateway_operation
                     WHERE status = 'PENDING_RECONCILIATION'
                       AND (next_recovery_at IS NULL OR next_recovery_at <= ?)
                       AND (recovery_lease_until IS NULL OR recovery_lease_until < ?)
                     ORDER BY next_recovery_at NULLS FIRST, updated_at ASC
                     LIMIT ?
                     FOR UPDATE SKIP LOCKED
                )
                UPDATE payment_gateway.gateway_operation operation
                   SET recovery_lease_until = ?,
                       recovery_attempt_count = operation.recovery_attempt_count + 1,
                       updated_at = ?
                  FROM due
                 WHERE operation.id = due.id
                RETURNING operation.id, operation.route_id, operation.operation_id, operation.idempotency_key,
                          operation.provider_reference, operation.recovery_attempt_count
                """,
                this::mapRecoveryRecord,
                offset(now),
                offset(now),
                limit,
                offset(leaseUntil),
                offset(now)
        );
    }

    private void scheduleRecovery(
            UUID operationId,
            String code,
            int recoveryAttemptCount,
            Instant now,
            String providerReference
    ) {
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_operation
                   SET error_code = ?, last_recovery_error_code = ?,
                       recovery_lease_until = NULL, next_recovery_at = ?,
                       provider_reference = COALESCE(?, provider_reference), updated_at = ?
                 WHERE id = ? AND status = 'PENDING_RECONCILIATION'
                """,
                boundedCode(code),
                boundedCode(code),
                offset(recoveryPolicy.nextAttemptAt(now, recoveryAttemptCount)),
                blankToNull(providerReference),
                offset(now),
                operationId
        );
    }

    private GatewayOperationResult findByIdempotency(String operationType, String idempotencyKey) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, status, error_code, provider_reference, public_transaction_reference, updated_at
                  FROM payment_gateway.gateway_operation
                 WHERE operation_type = ? AND idempotency_key = ?
                """,
                this::mapOperation,
                operationType,
                idempotencyKey.trim()
        ));
    }

    private GatewayOperationResult mapOperation(ResultSet rs, int rowNum) throws SQLException {
        String errorCode = rs.getString("error_code");
        GatewayOperationStatus status = GatewayOperationStatus.valueOf(rs.getString("status"));
        return new GatewayOperationResult(
                rs.getObject("id", UUID.class),
                status,
                errorCode == null ? defaultCodeForStatus(status) : errorCode,
                rs.getString("provider_reference"),
                rs.getString("public_transaction_reference"),
                instant(rs, "updated_at")
        );
    }

    private String defaultCodeForStatus(GatewayOperationStatus status) {
        return switch (status) {
            case SUCCEEDED -> "APPROVED";
            case DECLINED -> "DECLINED";
            case ACTION_REQUIRED -> "ACTION_REQUIRED";
            case PENDING_RECONCILIATION -> "PENDING_RECONCILIATION";
            case FAILED -> "FAILED";
        };
    }

    private GatewayOperationRecoveryRecord mapRecoveryRecord(ResultSet rs, int rowNum) throws SQLException {
        return new GatewayOperationRecoveryRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getString("operation_id"),
                rs.getString("idempotency_key"),
                rs.getString("provider_reference"),
                rs.getInt("recovery_attempt_count")
        );
    }

    private Set<GatewayCapability> requiredCapability(com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType type) {
        return switch (type) {
            case AUTHORIZE -> Set.of(GatewayCapability.AUTHORIZE);
            case VOID -> Set.of(GatewayCapability.VOID);
            case CAPTURE -> Set.of(GatewayCapability.CAPTURE);
            case REFUND -> Set.of(GatewayCapability.REFUND);
            case STATUS_QUERY -> Set.of(GatewayCapability.STATUS_QUERY);
        };
    }

    private void rejectRawPaymentData(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        String digits = reference.replaceAll("\\D", "");
        if (digits.length() >= 12 && digits.length() <= 19) {
            throw new GatewayBusinessException("RAW_PAYMENT_DATA_FORBIDDEN", "Use a provider token, hosted checkout reference, or certified terminal reference.");
        }
    }

    private BigDecimal normalizeAmount(BigDecimal value) {
        if (value == null || value.signum() < 0) {
            throw new GatewayBusinessException("INVALID_AMOUNT", "Gateway amount must be zero or positive.");
        }
        return value;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String boundedCode(String value) {
        String normalized = value == null || value.isBlank() ? "RECOVERY_INQUIRY_FAILED" : value.trim();
        return normalized.length() <= 96 ? normalized : normalized.substring(0, 96);
    }

    private OffsetDateTime offset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private record GatewayOperationRecoveryRecord(
            UUID id,
            UUID routeId,
            String operationId,
            String idempotencyKey,
            String providerReference,
            int recoveryAttemptCount
    ) {
    }
}
