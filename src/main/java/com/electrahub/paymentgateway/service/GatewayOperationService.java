package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayAction;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayActionType;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;
import java.util.UUID;

@Service
public class GatewayOperationService {

    private final JdbcTemplate jdbcTemplate;
    private final GatewayConfigurationService configurationService;
    private final GatewayRoutePolicy routePolicy;
    private final PaymentGatewayRegistry adapterRegistry;
    private final GatewayOperationRecoveryPolicy recoveryPolicy;
    private final ObjectMapper objectMapper;
    private final GatewayPaymentMethodVault paymentMethodVault;
    private final ProviderTokenCipher tokenCipher;

    public GatewayOperationService(
            JdbcTemplate jdbcTemplate,
            GatewayConfigurationService configurationService,
            GatewayRoutePolicy routePolicy,
            PaymentGatewayRegistry adapterRegistry,
            GatewayOperationRecoveryPolicy recoveryPolicy,
            ObjectMapper objectMapper,
            GatewayPaymentMethodVault paymentMethodVault,
            ProviderTokenCipher tokenCipher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.configurationService = configurationService;
        this.routePolicy = routePolicy;
        this.adapterRegistry = adapterRegistry;
        this.recoveryPolicy = recoveryPolicy;
        this.objectMapper = objectMapper;
        this.paymentMethodVault = paymentMethodVault;
        this.tokenCipher = tokenCipher;
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
        RouteResolution decision = routePolicy.evaluateOperation(
                candidate.route(),
                candidate.connection(),
                request.operationType(),
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
                         operation_type, idempotency_key, amount, currency, status, provider_reference,
                         error_code, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING_RECONCILIATION', ?,
                            'PENDING_RECONCILIATION', ?, ?)
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
                    blankToNull(request.providerReference()),
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
            GatewayOperationRequest providerRequest = withProviderToken(request, candidate.connection());
            GatewayOperationResult adapterResult = adapter.execute(providerRequest, candidate.connection());
            GatewayOperationResult persisted = new GatewayOperationResult(
                    gatewayOperationId,
                    adapterResult.status(),
                    adapterResult.code(),
                    adapterResult.providerReference(),
                    adapterResult.publicTransactionReference(),
                    adapterResult.action(),
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
                    blankToNull(request.providerReference()),
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
                    gatewayOperationId, status, ex.code(), null, null, null, Instant.now());
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
                    blankToNull(request.providerReference()),
                    null,
                    null,
                    Instant.now()
            );
            persistResult(pending);
            scheduleRecovery(gatewayOperationId, pending.code(), 0, Instant.now(), null);
            return pending;
        }
    }

    private GatewayOperationRequest withProviderToken(GatewayOperationRequest request, com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection connection) {
        if (request.operationType() != com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType.AUTHORIZE
                || request.paymentMethodReference() == null || request.paymentMethodReference().isBlank()) {
            return request;
        }
        GatewayPaymentMethodVault.ResolvedGatewayPaymentMethod paymentMethod = paymentMethodVault.resolve(
                request.paymentMethodReference(), request.accountReference(), connection
        );
        if (paymentMethod.providerToken().equals(request.paymentMethodReference())
                && paymentMethod.providerCustomerReference() == null) {
            return request;
        }
        return new GatewayOperationRequest(
                request.routeId(), request.paymentIntentId(), request.paymentAttemptId(), request.operationId(),
                request.idempotencyKey(), request.operationType(), request.amount(), request.currency(),
                request.accountReference(), paymentMethod.providerToken(), paymentMethod.providerCustomerReference(),
                request.providerReference(), request.returnUrl(), request.requestedAt()
        );
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
                                operation.id(), operation.operationId(), operation.idempotencyKey(),
                                operation.providerReference(), operation.operationType(),
                                operation.publicTransactionReference(), operation.amount(), operation.currency()),
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
                        providerStatus.action(),
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
                       action_type = ?, action_url = ?, action_client_secret = ?, action_expires_at = ?, action_data = ?,
                       recovery_lease_until = NULL, next_recovery_at = NULL, last_recovery_error_code = NULL, updated_at = ?
                 WHERE id = ?
                """,
                result.status().name(),
                result.status() == GatewayOperationStatus.SUCCEEDED && "APPROVED".equals(result.code())
                        ? null
                        : result.code(),
                result.providerReference(),
                result.publicTransactionReference(),
                result.action() == null ? null : result.action().type().name(),
                result.action() == null ? null : result.action().url(),
                encryptedActionSecret(result),
                result.action() == null || result.action().expiresAt() == null ? null : offset(result.action().expiresAt()),
                encodeActionData(result.action()),
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
                          operation.provider_reference, operation.operation_type,
                          operation.public_transaction_reference, operation.amount, operation.currency,
                          operation.recovery_attempt_count
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
                SELECT id, status, error_code, provider_reference, public_transaction_reference,
                       action_type, action_url, action_client_secret, action_expires_at, action_data, updated_at
                  FROM payment_gateway.gateway_operation
                 WHERE operation_type = ? AND idempotency_key = ?
                """,
                this::mapOperation,
                operationType,
                idempotencyKey.trim()
        ));
    }

    public GatewayOperationResult find(UUID operationId) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, status, error_code, provider_reference, public_transaction_reference,
                       action_type, action_url, action_client_secret, action_expires_at, action_data, updated_at
                  FROM payment_gateway.gateway_operation
                 WHERE id = ?
                """,
                this::mapOperation,
                operationId
        ));
    }

    /**
     * Performs a read-only provider inquiry after a customer completes a redirect or SDK action.
     * The original financial mutation is never replayed.
     */
    public GatewayOperationResult refresh(UUID operationId) {
        GatewayOperationResult existing = find(operationId);
        if (existing == null || isTerminal(existing.status())) {
            return existing;
        }
        GatewayOperationRecoveryRecord operation = findRecoveryRecord(operationId);
        if (operation == null || operation.providerReference() == null || operation.providerReference().isBlank()) {
            return existing;
        }

        GatewayRouteCandidate candidate = configurationService.requireRouteCandidate(operation.routeId());
        PaymentGatewayAdapter adapter = adapterRegistry.find(candidate.connection().provider())
                .orElseThrow(() -> new GatewayBusinessException(
                        "ADAPTER_NOT_INSTALLED", "The payment provider adapter is not installed."
                ));
        GatewayOperationResult providerStatus = adapter.queryStatus(
                new com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery(
                        operation.id(), operation.operationId(), operation.idempotencyKey(),
                        operation.providerReference(), operation.operationType(),
                        operation.publicTransactionReference(), operation.amount(), operation.currency()
                ),
                candidate.connection()
        );
        GatewayOperationResult refreshed = new GatewayOperationResult(
                operation.id(),
                providerStatus.status(),
                providerStatus.code(),
                blankToNull(providerStatus.providerReference()) == null
                        ? existing.providerReference() : providerStatus.providerReference(),
                blankToNull(providerStatus.publicTransactionReference()) == null
                        ? existing.publicTransactionReference() : providerStatus.publicTransactionReference(),
                providerStatus.action() == null && !isTerminal(providerStatus.status())
                        ? existing.action() : providerStatus.action(),
                providerStatus.processedAt()
        );
        persistResult(refreshed);
        if (refreshed.status() == GatewayOperationStatus.PENDING_RECONCILIATION) {
            scheduleRecovery(operation.id(), refreshed.code(), operation.recoveryAttemptCount(), Instant.now(), refreshed.providerReference());
        }
        return find(operationId);
    }

    private GatewayOperationRecoveryRecord findRecoveryRecord(UUID operationId) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, route_id, operation_id, idempotency_key, provider_reference, operation_type,
                       public_transaction_reference, amount, currency, recovery_attempt_count
                  FROM payment_gateway.gateway_operation
                 WHERE id = ?
                """,
                this::mapRecoveryRecord,
                operationId
        ));
    }

    private boolean isTerminal(GatewayOperationStatus status) {
        return status == GatewayOperationStatus.SUCCEEDED
                || status == GatewayOperationStatus.DECLINED
                || status == GatewayOperationStatus.FAILED;
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
                mapAction(rs),
                instant(rs, "updated_at")
        );
    }

    private GatewayAction mapAction(ResultSet rs) throws SQLException {
        String actionType = rs.getString("action_type");
        if (actionType == null || actionType.isBlank()) {
            return null;
        }
        return new GatewayAction(
                GatewayActionType.valueOf(actionType),
                rs.getString("action_url"),
                decryptedActionSecret(rs),
                instant(rs, "action_expires_at"),
                decodeActionData(rs.getString("action_data"))
        );
    }

    private String encodeActionData(GatewayAction action) {
        if (action == null || action.data().isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(action.data());
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("INVALID_GATEWAY_ACTION", "The provider returned invalid customer action data.");
        }
    }

    private String encryptedActionSecret(GatewayOperationResult result) {
        if (result.action() == null || result.action().clientSecret() == null || !tokenCipher.configured()) {
            return null;
        }
        return tokenCipher.encrypt(result.action().clientSecret(), "gateway-action:" + result.gatewayOperationId());
    }

    private String decryptedActionSecret(ResultSet rs) throws SQLException {
        String stored = rs.getString("action_client_secret");
        if (stored == null || stored.isBlank() || !tokenCipher.configured()) {
            return null;
        }
        return tokenCipher.decrypt(stored, "gateway-action:" + rs.getObject("id", UUID.class));
    }

    private Map<String, String> decodeActionData(String value) {
        if (value == null || value.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
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
                GatewayOperationType.valueOf(rs.getString("operation_type")),
                rs.getString("public_transaction_reference"),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getInt("recovery_attempt_count")
        );
    }

    static void rejectRawPaymentData(String reference) {
        if (reference == null || reference.isBlank()) {
            return;
        }
        String normalized = reference.trim();
        if (!normalized.matches("[0-9 -]+")) {
            return;
        }
        String digits = normalized.replaceAll("[ -]", "");
        if (digits.length() >= 12 && digits.length() <= 19 && passesLuhnCheck(digits)) {
            throw new GatewayBusinessException("RAW_PAYMENT_DATA_FORBIDDEN", "Use a provider token, hosted checkout reference, or certified terminal reference.");
        }
    }

    private static boolean passesLuhnCheck(String digits) {
        int sum = 0;
        boolean doubleDigit = false;
        for (int index = digits.length() - 1; index >= 0; index--) {
            int digit = digits.charAt(index) - '0';
            if (doubleDigit) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubleDigit = !doubleDigit;
        }
        return sum % 10 == 0;
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
            GatewayOperationType operationType,
            String publicTransactionReference,
            BigDecimal amount,
            String currency,
            int recoveryAttemptCount
    ) {
    }
}
