package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateGatewayConnectionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateMerchantAccountRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreatePaymentRouteRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.MerchantAccount;
import com.electrahub.paymentgateway.domain.GatewayContracts.MerchantAccountStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.GatewayUnavailableException;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;

@Service
public class GatewayConfigurationService {

    private static final Logger log = LoggerFactory.getLogger(GatewayConfigurationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final PaymentGatewayRegistry adapterRegistry;
    private final GatewayRouteCache routeCache;

    public GatewayConfigurationService(
            JdbcTemplate jdbcTemplate,
            PaymentGatewayRegistry adapterRegistry,
            GatewayRouteCache routeCache
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.adapterRegistry = adapterRegistry;
        this.routeCache = routeCache;
    }

    public List<GatewayConnection> listConnections() {
        return jdbcTemplate.query(connectionSelect() + " ORDER BY created_at DESC", this::mapConnection);
    }

    public GatewayConnection createConnection(CreateGatewayConnectionRequest request, UUID actorId) {
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.gateway_connection
                    (id, provider_code, environment, status, adapter_version, endpoint_profile, capabilities,
                     credential_secret_reference, webhook_secret_reference, certificate_secret_reference,
                     configuration_version, created_at, updated_at)
                VALUES (?, ?, ?, 'DRAFT', 'unvalidated', ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                id,
                request.provider().name(),
                request.environment().name(),
                required(request.endpointProfile(), "endpointProfile", 80),
                encodeCapabilities(request.requestedCapabilities()),
                secretReference(request.credentialSecretReference()),
                secretReference(request.webhookSecretReference()),
                secretReference(request.certificateSecretReference()),
                offset(now),
                offset(now)
        );
        GatewayConnection saved = requireConnection(id);
        audit(actorId, "GATEWAY_CONNECTION_CREATED", "GATEWAY_CONNECTION", id, null, connectionAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    /** The provider call runs without an open database transaction. */
    public GatewayConnection validateConnection(UUID connectionId, UUID actorId) {
        GatewayConnection existing = requireConnection(connectionId);
        jdbcTemplate.update(
                "UPDATE payment_gateway.gateway_connection SET status = 'VALIDATING', updated_at = ? WHERE id = ?",
                offset(Instant.now()), connectionId
        );
        GatewayConnection validating = requireConnection(connectionId);
        ConnectionValidation result = validateConnectionSafely(validating);
        Instant now = Instant.now();
        GatewayConnectionStatus status = result.valid() ? GatewayConnectionStatus.READY : GatewayConnectionStatus.DRAFT;
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_connection
                   SET status = ?, adapter_version = ?, capabilities = ?, validated_at = ?, last_health_at = ?,
                       last_error_code = ?, configuration_version = configuration_version + 1, updated_at = ?
                 WHERE id = ?
                """,
                status.name(),
                truncate(result.adapterVersion(), 64, "adapterVersion"),
                encodeCapabilities(result.capabilities()),
                result.valid() ? offset(now) : null,
                offset(now),
                result.valid() ? null : truncate(result.code(), 96, "errorCode"),
                offset(now),
                connectionId
        );
        GatewayConnection saved = requireConnection(connectionId);
        audit(actorId, "GATEWAY_CONNECTION_VALIDATED", "GATEWAY_CONNECTION", connectionId,
                connectionAudit(existing), connectionAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public GatewayConnection activateConnection(UUID connectionId, UUID actorId) {
        GatewayConnection existing = requireConnection(connectionId);
        if (existing.status() != GatewayConnectionStatus.READY) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_READY", "Validate the gateway connection before activation.");
        }
        if (existing.provider() != GatewayProvider.MOCK && !existing.credentialConfigured()) {
            throw new GatewayBusinessException("GATEWAY_CREDENTIAL_NOT_CONFIGURED", "A credential secret reference is required before activating this provider connection.");
        }
        if (existing.provider() == GatewayProvider.MOCK && existing.environment() == GatewayEnvironment.PRODUCTION) {
            throw new GatewayBusinessException("MOCK_NOT_PERMITTED_PRODUCTION", "Mock gateways cannot be activated in production.");
        }
        updateConnectionStatus(connectionId, GatewayConnectionStatus.ACTIVE, null);
        GatewayConnection saved = requireConnection(connectionId);
        audit(actorId, "GATEWAY_CONNECTION_ACTIVATED", "GATEWAY_CONNECTION", connectionId,
                connectionAudit(existing), connectionAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    private ConnectionValidation validateConnectionSafely(GatewayConnection connection) {
        try {
            return adapterRegistry.find(connection.provider())
                    .map(adapter -> adapter.validate(connection))
                    .orElse(new ConnectionValidation(false, "ADAPTER_NOT_INSTALLED", "No installed adapter supports this provider.", Set.of(), "unavailable"));
        } catch (GatewayUnavailableException exception) {
            return new ConnectionValidation(false, exception.code(), "The provider could not be reached during validation.", Set.of(), "unavailable");
        } catch (GatewayBusinessException exception) {
            return new ConnectionValidation(false, exception.code(), exception.getMessage(), Set.of(), "invalid");
        } catch (RuntimeException exception) {
            log.warn("Gateway connection validation failed for provider {}: {}", connection.provider(), exception.getClass().getSimpleName());
            return new ConnectionValidation(false, "GATEWAY_CONNECTION_VALIDATION_FAILED", "The provider connection could not be validated.", Set.of(), "failed");
        }
    }

    public GatewayConnection disableConnection(UUID connectionId, UUID actorId) {
        GatewayConnection existing = requireConnection(connectionId);
        updateConnectionStatus(connectionId, GatewayConnectionStatus.DISABLED, "DISABLED_BY_ADMIN");
        GatewayConnection saved = requireConnection(connectionId);
        audit(actorId, "GATEWAY_CONNECTION_DISABLED", "GATEWAY_CONNECTION", connectionId,
                connectionAudit(existing), connectionAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public List<MerchantAccount> listMerchantAccounts() {
        return jdbcTemplate.query(merchantSelect() + " ORDER BY created_at DESC", this::mapMerchantAccount);
    }

    public MerchantAccount createMerchantAccount(CreateMerchantAccountRequest request, UUID actorId) {
        GatewayConnection connection = requireConnection(request.connectionId());
        if (connection.status() != GatewayConnectionStatus.ACTIVE) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_ACTIVE", "Merchant accounts require an active gateway connection.");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.merchant_payment_account
                    (id, connection_id, enterprise_id, network_id, legal_entity_reference, provider_merchant_reference,
                     merchant_country, settlement_currency, supported_presentment_currencies, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
                """,
                id,
                request.connectionId(),
                nullable(request.enterpriseId(), 80),
                nullable(request.networkId(), 80),
                required(request.legalEntityReference(), "legalEntityReference", 160),
                required(request.providerMerchantReference(), "providerMerchantReference", 160),
                country(request.merchantCountry()),
                currency(request.settlementCurrency()),
                encodeStrings(request.supportedPresentmentCurrencies().isEmpty()
                        ? Set.of(request.settlementCurrency())
                        : request.supportedPresentmentCurrencies()),
                offset(now),
                offset(now)
        );
        MerchantAccount saved = requireMerchantAccount(id);
        audit(actorId, "MERCHANT_ACCOUNT_CREATED", "MERCHANT_ACCOUNT", id, null, merchantAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public MerchantAccount activateMerchantAccount(UUID merchantAccountId, UUID actorId) {
        MerchantAccount existing = requireMerchantAccount(merchantAccountId);
        GatewayConnection connection = requireConnection(existing.connectionId());
        if (connection.status() != GatewayConnectionStatus.ACTIVE) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_ACTIVE", "Activate the gateway connection before activating its merchant account.");
        }
        updateMerchantAccountStatus(merchantAccountId, MerchantAccountStatus.ACTIVE);
        MerchantAccount saved = requireMerchantAccount(merchantAccountId);
        audit(actorId, "MERCHANT_ACCOUNT_ACTIVATED", "MERCHANT_ACCOUNT", merchantAccountId,
                merchantAudit(existing), merchantAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public MerchantAccount disableMerchantAccount(UUID merchantAccountId, UUID actorId) {
        MerchantAccount existing = requireMerchantAccount(merchantAccountId);
        updateMerchantAccountStatus(merchantAccountId, MerchantAccountStatus.DISABLED);
        MerchantAccount saved = requireMerchantAccount(merchantAccountId);
        audit(actorId, "MERCHANT_ACCOUNT_DISABLED", "MERCHANT_ACCOUNT", merchantAccountId,
                merchantAudit(existing), merchantAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public List<PaymentRoute> listPaymentRoutes() {
        return jdbcTemplate.query(routeSelect() + " ORDER BY priority ASC, created_at DESC", this::mapPaymentRoute);
    }

    public PaymentRoute createPaymentRoute(CreatePaymentRouteRequest request, UUID actorId) {
        MerchantAccount merchant = requireMerchantAccount(request.merchantAccountId());
        GatewayConnection connection = requireConnection(merchant.connectionId());
        if (connection.status() != GatewayConnectionStatus.ACTIVE || merchant.status() != MerchantAccountStatus.ACTIVE) {
            throw new GatewayBusinessException("PAYMENT_ROUTE_NOT_CONFIGURED", "An active connection and merchant account are required.");
        }
        String settlementCurrency = currency(request.settlementCurrency());
        if (!merchant.settlementCurrency().equals(settlementCurrency)) {
            throw new GatewayBusinessException("MERCHANT_SETTLEMENT_CURRENCY_UNSUPPORTED", "Route settlement currency must match the merchant account settlement currency.");
        }
        Set<GatewayCapability> capabilities = request.requiredCapabilities() == null ? Set.of() : request.requiredCapabilities();
        if (!connection.capabilities().containsAll(capabilities)) {
            throw new GatewayBusinessException("PAYMENT_METHOD_NOT_SUPPORTED", "The connection does not support the requested route capabilities.");
        }
        if (request.effectiveFrom() != null && request.effectiveTo() != null && !request.effectiveTo().isAfter(request.effectiveFrom())) {
            throw new GatewayBusinessException("INVALID_EFFECTIVE_PERIOD", "Route effectiveTo must be after effectiveFrom.");
        }
        Instant now = Instant.now();
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.payment_route
                    (id, merchant_account_id, charging_country, presentment_currency, settlement_currency,
                     channel, payment_method, priority, enabled, required_capabilities, effective_from, effective_to,
                     configuration_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)
                """,
                id,
                merchant.id(),
                country(request.chargingCountry()),
                currency(request.presentmentCurrency()),
                settlementCurrency,
                request.channel().name(),
                request.paymentMethod().name(),
                Math.max(0, request.priority()),
                request.enabled(),
                encodeCapabilities(capabilities),
                request.effectiveFrom() == null ? null : offset(request.effectiveFrom()),
                request.effectiveTo() == null ? null : offset(request.effectiveTo()),
                offset(now),
                offset(now)
        );
        PaymentRoute saved = requirePaymentRoute(id);
        audit(actorId, "PAYMENT_ROUTE_CREATED", "PAYMENT_ROUTE", id, null, routeAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public PaymentRoute setPaymentRouteEnabled(UUID routeId, boolean enabled, UUID actorId) {
        PaymentRoute existing = requirePaymentRoute(routeId);
        if (enabled) {
            MerchantAccount merchant = requireMerchantAccount(existing.merchantAccountId());
            GatewayConnection connection = requireConnection(merchant.connectionId());
            if (merchant.status() != MerchantAccountStatus.ACTIVE || connection.status() != GatewayConnectionStatus.ACTIVE) {
                throw new GatewayBusinessException("PAYMENT_ROUTE_NOT_CONFIGURED", "An active merchant account and gateway connection are required before enabling a route.");
            }
        }
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.payment_route
                   SET enabled = ?, configuration_version = configuration_version + 1, updated_at = ?
                 WHERE id = ?
                """,
                enabled,
                offset(Instant.now()),
                routeId
        );
        PaymentRoute saved = requirePaymentRoute(routeId);
        audit(actorId, enabled ? "PAYMENT_ROUTE_ENABLED" : "PAYMENT_ROUTE_DISABLED", "PAYMENT_ROUTE", routeId,
                routeAudit(existing), routeAudit(saved));
        routeCache.invalidateAll();
        return saved;
    }

    public GatewayRouteCandidate requireRouteCandidate(UUID routeId) {
        GatewayRouteCandidate candidate = DataAccessUtils.singleResult(jdbcTemplate.query(
                routeWithConnectionSelect() + " WHERE r.id = ?", this::mapRouteCandidate, routeId));
        if (candidate == null) {
            throw new GatewayBusinessException("PAYMENT_ROUTE_NOT_CONFIGURED", "Payment route was not found.");
        }
        return candidate;
    }

    public List<GatewayRouteCandidate> findCandidates(RouteResolutionRequest request) {
        return jdbcTemplate.query(
                routeWithConnectionSelect() + """
                     WHERE r.merchant_account_id = ?
                       AND r.charging_country = ?
                       AND r.presentment_currency = ?
                       AND r.settlement_currency = ?
                       AND r.channel = ?
                       AND r.payment_method = ?
                     ORDER BY r.priority ASC, r.configuration_version DESC
                """,
                this::mapRouteCandidate,
                request.merchantAccountId(),
                country(request.chargingCountry()),
                currency(request.presentmentCurrency()),
                currency(request.settlementCurrency()),
                request.channel().name(),
                request.paymentMethod().name()
        );
    }

    /**
     * Resolves only merchant accounts that belong to the session's canonical network. An
     * enterprise-level account is a deliberate fallback when the network does not have its own
     * account. There is no cross-enterprise or cross-network fallback.
     */
    public List<GatewayRouteCandidate> findCandidates(ScopedRouteResolutionRequest request) {
        String enterpriseId = required(request.enterpriseId(), "enterpriseId", 80);
        String networkId = required(request.networkId(), "networkId", 80);
        return jdbcTemplate.query(
                routeWithConnectionSelect() + """
                     WHERE m.status = 'ACTIVE'
                       AND r.charging_country = ?
                       AND r.presentment_currency = ?
                       AND r.channel = ?
                       AND r.payment_method = ?
                       AND (
                            (m.enterprise_id = ? AND m.network_id = ?)
                            OR (m.enterprise_id = ? AND m.network_id IS NULL)
                       )
                     ORDER BY CASE WHEN m.network_id = ? THEN 0 ELSE 1 END,
                              r.priority ASC,
                              r.configuration_version DESC,
                              r.id ASC
                """,
                this::mapRouteCandidate,
                country(request.chargingCountry()),
                currency(request.presentmentCurrency()),
                request.channel().name(),
                request.paymentMethod().name(),
                enterpriseId,
                networkId,
                enterpriseId,
                networkId
        );
    }

    GatewayConnection requireConnection(UUID id) {
        GatewayConnection connection = DataAccessUtils.singleResult(jdbcTemplate.query(
                connectionSelect() + " WHERE id = ?", this::mapConnection, id));
        if (connection == null) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_FOUND", "Gateway connection was not found.");
        }
        return connection;
    }

    private MerchantAccount requireMerchantAccount(UUID id) {
        MerchantAccount account = DataAccessUtils.singleResult(jdbcTemplate.query(
                merchantSelect() + " WHERE id = ?", this::mapMerchantAccount, id));
        if (account == null) {
            throw new GatewayBusinessException("MERCHANT_ACCOUNT_NOT_FOUND", "Merchant payment account was not found.");
        }
        return account;
    }

    private PaymentRoute requirePaymentRoute(UUID id) {
        PaymentRoute route = DataAccessUtils.singleResult(jdbcTemplate.query(
                routeSelect() + " WHERE id = ?", this::mapPaymentRoute, id));
        if (route == null) {
            throw new GatewayBusinessException("PAYMENT_ROUTE_NOT_CONFIGURED", "Payment route was not found.");
        }
        return route;
    }

    private void updateConnectionStatus(UUID id, GatewayConnectionStatus status, String errorCode) {
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_connection
                   SET status = ?, last_error_code = ?, configuration_version = configuration_version + 1, updated_at = ?
                 WHERE id = ?
                """,
                status.name(),
                errorCode,
                offset(Instant.now()),
                id
        );
    }

    private void updateMerchantAccountStatus(UUID id, MerchantAccountStatus status) {
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.merchant_payment_account
                   SET status = ?, updated_at = ?
                 WHERE id = ?
                """,
                status.name(),
                offset(Instant.now()),
                id
        );
    }

    private void audit(UUID actorId, String action, String resourceType, UUID resourceId, String before, String after) {
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.gateway_configuration_audit
                    (id, actor_id, action, resource_type, resource_id, before_state, after_state, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(), actorId, action, resourceType, resourceId, before, after, offset(Instant.now())
        );
    }

    private GatewayConnection mapConnection(ResultSet rs, int rowNum) throws SQLException {
        return new GatewayConnection(
                rs.getObject("id", UUID.class),
                GatewayProvider.valueOf(rs.getString("provider_code")),
                GatewayEnvironment.valueOf(rs.getString("environment")),
                GatewayConnectionStatus.valueOf(rs.getString("status")),
                rs.getString("adapter_version"),
                rs.getString("endpoint_profile"),
                decodeCapabilities(rs.getString("capabilities")),
                rs.getString("credential_secret_reference") != null,
                rs.getString("webhook_secret_reference") != null,
                rs.getString("certificate_secret_reference") != null,
                instant(rs, "validated_at"),
                instant(rs, "last_health_at"),
                rs.getString("last_error_code"),
                rs.getInt("configuration_version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private MerchantAccount mapMerchantAccount(ResultSet rs, int rowNum) throws SQLException {
        return new MerchantAccount(
                rs.getObject("id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getString("enterprise_id"),
                rs.getString("network_id"),
                rs.getString("legal_entity_reference"),
                rs.getString("provider_merchant_reference"),
                rs.getString("merchant_country"),
                rs.getString("settlement_currency"),
                decodeStrings(rs.getString("supported_presentment_currencies")),
                MerchantAccountStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private PaymentRoute mapPaymentRoute(ResultSet rs, int rowNum) throws SQLException {
        return new PaymentRoute(
                rs.getObject("id", UUID.class),
                rs.getObject("merchant_account_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getString("charging_country"),
                rs.getString("presentment_currency"),
                rs.getString("settlement_currency"),
                PaymentChannel.valueOf(rs.getString("channel")),
                PaymentMethodType.valueOf(rs.getString("payment_method")),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                decodeCapabilities(rs.getString("required_capabilities")),
                instant(rs, "effective_from"),
                instant(rs, "effective_to"),
                rs.getInt("configuration_version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private GatewayRouteCandidate mapRouteCandidate(ResultSet rs, int rowNum) throws SQLException {
        PaymentRoute route = new PaymentRoute(
                rs.getObject("route_id", UUID.class),
                rs.getObject("merchant_account_id", UUID.class),
                rs.getObject("route_connection_id", UUID.class),
                rs.getString("charging_country"),
                rs.getString("presentment_currency"),
                rs.getString("settlement_currency"),
                PaymentChannel.valueOf(rs.getString("channel")),
                PaymentMethodType.valueOf(rs.getString("payment_method")),
                rs.getInt("priority"),
                rs.getBoolean("enabled"),
                decodeCapabilities(rs.getString("required_capabilities")),
                instant(rs, "effective_from"),
                instant(rs, "effective_to"),
                rs.getInt("route_configuration_version"),
                instant(rs, "route_created_at"),
                instant(rs, "route_updated_at")
        );
        GatewayConnection connection = new GatewayConnection(
                rs.getObject("connection_id", UUID.class),
                GatewayProvider.valueOf(rs.getString("provider_code")),
                GatewayEnvironment.valueOf(rs.getString("environment")),
                GatewayConnectionStatus.valueOf(rs.getString("connection_status")),
                rs.getString("adapter_version"),
                rs.getString("endpoint_profile"),
                decodeCapabilities(rs.getString("capabilities")),
                rs.getBoolean("credential_configured"),
                rs.getBoolean("webhook_secret_configured"),
                rs.getBoolean("certificate_configured"),
                instant(rs, "validated_at"),
                instant(rs, "last_health_at"),
                rs.getString("last_error_code"),
                rs.getInt("connection_configuration_version"),
                instant(rs, "connection_created_at"),
                instant(rs, "connection_updated_at")
        );
        return new GatewayRouteCandidate(route, connection);
    }

    private String connectionSelect() {
        return """
                SELECT id, provider_code, environment, status, adapter_version, endpoint_profile, capabilities,
                       credential_secret_reference, webhook_secret_reference, certificate_secret_reference,
                       validated_at, last_health_at, last_error_code, configuration_version, created_at, updated_at
                  FROM payment_gateway.gateway_connection
                """;
    }

    private String merchantSelect() {
        return """
                SELECT id, connection_id, enterprise_id, network_id, legal_entity_reference, provider_merchant_reference,
                       merchant_country, settlement_currency, supported_presentment_currencies, status, created_at, updated_at
                  FROM payment_gateway.merchant_payment_account
                """;
    }

    private String routeSelect() {
        return """
                SELECT r.id, r.merchant_account_id, m.connection_id, r.charging_country, r.presentment_currency,
                       r.settlement_currency, r.channel, r.payment_method, r.priority, r.enabled,
                       r.required_capabilities, r.effective_from, r.effective_to, r.configuration_version,
                       r.created_at, r.updated_at
                  FROM payment_gateway.payment_route r
                  JOIN payment_gateway.merchant_payment_account m ON m.id = r.merchant_account_id
                """;
    }

    private String routeWithConnectionSelect() {
        return """
                SELECT r.id AS route_id, r.merchant_account_id, m.connection_id AS route_connection_id,
                       r.charging_country, r.presentment_currency, r.settlement_currency, r.channel, r.payment_method,
                       r.priority, r.enabled, r.required_capabilities, r.effective_from, r.effective_to,
                       r.configuration_version AS route_configuration_version, r.created_at AS route_created_at,
                       r.updated_at AS route_updated_at,
                       c.id AS connection_id, c.provider_code, c.environment, c.status AS connection_status,
                       c.adapter_version, c.endpoint_profile, c.capabilities,
                       (c.credential_secret_reference IS NOT NULL) AS credential_configured,
                       (c.webhook_secret_reference IS NOT NULL) AS webhook_secret_configured,
                       (c.certificate_secret_reference IS NOT NULL) AS certificate_configured,
                       c.validated_at, c.last_health_at, c.last_error_code,
                       c.configuration_version AS connection_configuration_version,
                       c.created_at AS connection_created_at, c.updated_at AS connection_updated_at
                  FROM payment_gateway.payment_route r
                  JOIN payment_gateway.merchant_payment_account m ON m.id = r.merchant_account_id
                  JOIN payment_gateway.gateway_connection c ON c.id = m.connection_id
                """;
    }

    private Set<GatewayCapability> decodeCapabilities(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        EnumSet<GatewayCapability> capabilities = EnumSet.noneOf(GatewayCapability.class);
        for (String candidate : value.split(",")) {
            if (!candidate.isBlank()) {
                capabilities.add(GatewayCapability.valueOf(candidate.trim()));
            }
        }
        return Set.copyOf(capabilities);
    }

    private Set<String> decodeStrings(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    private String encodeCapabilities(Set<GatewayCapability> capabilities) {
        return (capabilities == null ? Set.<GatewayCapability>of() : capabilities).stream()
                .map(Enum::name)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String encodeStrings(Set<String> values) {
        return (values == null ? Set.<String>of() : values).stream()
                .map(this::currency)
                .sorted()
                .collect(Collectors.joining(","));
    }

    private String country(String value) {
        String normalized = required(value, "country", 2).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw new GatewayBusinessException("INVALID_COUNTRY", "Country must use an ISO 3166-1 alpha-2 code.");
        }
        return normalized;
    }

    private String currency(String value) {
        String normalized = required(value, "currency", 3).toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new GatewayBusinessException("INVALID_CURRENCY", "Currency must use an ISO 4217 alpha-3 code.");
        }
        return normalized;
    }

    private String secretReference(String value) {
        String normalized = nullable(value, 256);
        if (normalized != null && !normalized.matches("[A-Za-z0-9][A-Za-z0-9_./:-]{2,255}")) {
            throw new GatewayBusinessException("INVALID_SECRET_REFERENCE", "Gateway credentials must be referenced by an approved secret identifier.");
        }
        return normalized;
    }

    private String required(String value, String field, int maxLength) {
        String normalized = nullable(value, maxLength);
        if (normalized == null) {
            throw new GatewayBusinessException("INVALID_CONFIGURATION", field + " is required.");
        }
        return normalized;
    }

    private String nullable(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return truncate(value.trim(), maxLength, "value");
    }

    private String truncate(String value, int maxLength, String field) {
        if (value != null && value.length() > maxLength) {
            throw new GatewayBusinessException("INVALID_CONFIGURATION", field + " exceeds the allowed length.");
        }
        return value;
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private String connectionAudit(GatewayConnection value) {
        return "{\"provider\":\"%s\",\"environment\":\"%s\",\"status\":\"%s\",\"version\":%d}"
                .formatted(value.provider(), value.environment(), value.status(), value.configurationVersion());
    }

    private String merchantAudit(MerchantAccount value) {
        return "{\"connectionId\":\"%s\",\"merchantCountry\":\"%s\",\"settlementCurrency\":\"%s\",\"status\":\"%s\"}"
                .formatted(value.connectionId(), value.merchantCountry(), value.settlementCurrency(), value.status());
    }

    private String routeAudit(PaymentRoute value) {
        return "{\"merchantAccountId\":\"%s\",\"chargingCountry\":\"%s\",\"presentmentCurrency\":\"%s\",\"settlementCurrency\":\"%s\",\"enabled\":%s,\"version\":%d}"
                .formatted(value.merchantAccountId(), value.chargingCountry(), value.presentmentCurrency(),
                        value.settlementCurrency(), value.enabled(), value.configurationVersion());
    }

}
