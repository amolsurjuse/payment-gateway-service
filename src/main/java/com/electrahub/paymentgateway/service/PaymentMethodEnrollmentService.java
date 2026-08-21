package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.CompleteGatewayPaymentMethodEnrollmentRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateGatewayPaymentMethodEnrollmentRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodEnrollment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodEnrollmentCompletion;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodRegistration;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodEnrollmentStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.domain.GatewayContracts.RegisterGatewayPaymentMethodRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.PaymentMethodEnrollmentProvider;
import com.electrahub.paymentgateway.service.spi.PaymentMethodEnrollmentProvider.ProviderCustomer;
import com.electrahub.paymentgateway.service.spi.PaymentMethodEnrollmentProvider.ProviderEnrollment;
import com.electrahub.paymentgateway.service.spi.PaymentMethodEnrollmentProvider.ProviderEnrollmentResult;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class PaymentMethodEnrollmentService {

    private final JdbcTemplate jdbcTemplate;
    private final PaymentRouteResolver routeResolver;
    private final GatewayConfigurationService configurationService;
    private final GatewayPaymentMethodVault paymentMethodVault;
    private final Map<GatewayProvider, PaymentMethodEnrollmentProvider> providers;

    public PaymentMethodEnrollmentService(
            JdbcTemplate jdbcTemplate,
            PaymentRouteResolver routeResolver,
            GatewayConfigurationService configurationService,
            GatewayPaymentMethodVault paymentMethodVault,
            List<PaymentMethodEnrollmentProvider> providers
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.routeResolver = routeResolver;
        this.configurationService = configurationService;
        this.paymentMethodVault = paymentMethodVault;
        EnumMap<GatewayProvider, PaymentMethodEnrollmentProvider> mapped = new EnumMap<>(GatewayProvider.class);
        providers.forEach(provider -> mapped.put(provider.provider(), provider));
        this.providers = Map.copyOf(mapped);
    }

    @Transactional
    public GatewayPaymentMethodEnrollment start(CreateGatewayPaymentMethodEnrollmentRequest request) {
        RouteResolution resolution = routeResolver.resolve(new ScopedRouteResolutionRequest(
                request.enterpriseId(),
                request.networkId(),
                request.chargingCountry().toUpperCase(Locale.ROOT),
                request.currency().toUpperCase(Locale.ROOT),
                PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE,
                Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.CAPTURE, GatewayCapability.THREE_DS_SCA)
        ));
        if (!resolution.approved() || resolution.routeId() == null) {
            throw new GatewayBusinessException(resolution.code(), resolution.message());
        }
        GatewayRouteCandidate candidate = configurationService.requireRouteCandidate(resolution.routeId());
        GatewayConnection connection = candidate.connection();
        if (connection.status() != GatewayConnectionStatus.ACTIVE || !candidate.route().enabled()) {
            throw new GatewayBusinessException("PAYMENT_ROUTE_NOT_ACTIVE", "Payment card enrollment requires an active route.");
        }
        PaymentMethodEnrollmentProvider provider = requireProvider(connection.provider());
        String accountHash = GatewayAccountReferenceHasher.hash(request.accountReference());
        String customerReference = requireProviderCustomer(provider, connection, accountHash);
        UUID enrollmentId = UUID.randomUUID();
        ProviderEnrollment providerEnrollment = provider.start(
                connection,
                customerReference,
                enrollmentId,
                request.chargingCountry().trim().toUpperCase(Locale.ROOT),
                request.currency().trim().toUpperCase(Locale.ROOT),
                validateReturnUrl(request.returnUrl())
        );
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.gateway_payment_method_enrollment
                    (id, route_id, connection_id, network_id, account_reference_hash, provider_code,
                     provider_enrollment_reference, provider_customer_reference, status, currency,
                     expires_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', ?, ?, ?, ?)
                """,
                enrollmentId,
                candidate.route().id(),
                connection.id(),
                request.networkId().trim(),
                accountHash,
                connection.provider().name(),
                providerEnrollment.reference(),
                customerReference,
                request.currency().trim().toUpperCase(Locale.ROOT),
                offset(providerEnrollment.expiresAt()),
                offset(now),
                offset(now)
        );
        return new GatewayPaymentMethodEnrollment(
                enrollmentId,
                candidate.route().id(),
                connection.id(),
                connection.provider(),
                connection.environment(),
                PaymentMethodEnrollmentStatus.CREATED,
                providerEnrollment.reference(),
                providerEnrollment.clientSecret(),
                providerEnrollment.publishableKey(),
                request.currency().trim().toUpperCase(Locale.ROOT),
                providerEnrollment.expiresAt()
        );
    }

    @Transactional
    public GatewayPaymentMethodEnrollmentCompletion complete(
            UUID enrollmentId,
            CompleteGatewayPaymentMethodEnrollmentRequest request
    ) {
        String accountHash = GatewayAccountReferenceHasher.hash(request.accountReference());
        EnrollmentRow enrollment = findEnrollment(enrollmentId, accountHash, true);
        if (enrollment == null) {
            throw new GatewayBusinessException("PAYMENT_METHOD_ENROLLMENT_NOT_FOUND", "Payment method enrollment was not found.");
        }
        if (enrollment.status() == PaymentMethodEnrollmentStatus.SUCCEEDED) {
            return completed(enrollment, requireRegistration(enrollment.paymentMethodId()));
        }
        if (enrollment.expiresAt().isBefore(Instant.now())) {
            updateStatus(enrollment.id(), PaymentMethodEnrollmentStatus.EXPIRED, null);
            throw new GatewayBusinessException("PAYMENT_METHOD_ENROLLMENT_EXPIRED", "Payment method enrollment expired.");
        }

        GatewayConnection connection = configurationService.requireConnection(enrollment.connectionId());
        PaymentMethodEnrollmentProvider provider = requireProvider(connection.provider());
        ProviderEnrollmentResult result = provider.retrieve(
                connection, enrollment.providerEnrollmentReference(), request.providerResult());
        if (!"succeeded".equalsIgnoreCase(result.status())) {
            if (Set.of("canceled", "requires_payment_method").contains(result.status().toLowerCase(Locale.ROOT))) {
                updateStatus(enrollment.id(), PaymentMethodEnrollmentStatus.FAILED, null);
                throw new GatewayBusinessException("PAYMENT_METHOD_ENROLLMENT_FAILED", "The payment provider did not complete card enrollment.");
            }
            throw new GatewayBusinessException("PAYMENT_METHOD_ENROLLMENT_PENDING", "Payment method enrollment is still pending.");
        }
        if (!enrollment.providerCustomerReference().equals(result.providerCustomerReference())) {
            throw new GatewayBusinessException("PAYMENT_METHOD_CUSTOMER_MISMATCH", "Payment method customer binding is invalid.");
        }
        validateCard(result);
        GatewayPaymentMethodRegistration registration = paymentMethodVault.register(
                new RegisterGatewayPaymentMethodRequest(
                        connection.id(),
                        request.accountReference(),
                        result.paymentMethodReference(),
                        result.brand(),
                        result.last4(),
                        result.expiryMonth(),
                        result.expiryYear()
                ),
                result.providerCustomerReference()
        );
        updateStatus(enrollment.id(), PaymentMethodEnrollmentStatus.SUCCEEDED, registration.id());
        return completed(enrollment, registration);
    }

    private String requireProviderCustomer(
            PaymentMethodEnrollmentProvider provider,
            GatewayConnection connection,
            String accountHash
    ) {
        String lockKey = connection.id() + ":" + accountHash;
        Long lockId = jdbcTemplate.queryForObject("SELECT hashtextextended(?, 0)", Long.class, lockKey);
        if (lockId == null) {
            throw new IllegalStateException("Unable to acquire provider customer lock.");
        }
        jdbcTemplate.execute("SELECT pg_advisory_xact_lock(" + lockId + ")");
        String existing = DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT provider_customer_reference
                  FROM payment_gateway.gateway_provider_customer
                 WHERE connection_id = ? AND account_reference_hash = ? AND active = TRUE
                """,
                (rs, rowNum) -> rs.getString("provider_customer_reference"),
                connection.id(),
                accountHash
        ));
        if (existing != null) {
            return existing;
        }
        ProviderCustomer customer = provider.createCustomer(connection, accountHash);
        Instant now = Instant.now();
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.gateway_provider_customer
                    (id, connection_id, account_reference_hash, provider_code,
                     provider_customer_reference, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                UUID.randomUUID(),
                connection.id(),
                accountHash,
                connection.provider().name(),
                customer.reference(),
                offset(now),
                offset(now)
        );
        return customer.reference();
    }

    private PaymentMethodEnrollmentProvider requireProvider(GatewayProvider provider) {
        PaymentMethodEnrollmentProvider adapter = providers.get(provider);
        if (adapter == null) {
            throw new GatewayBusinessException(
                    "PAYMENT_METHOD_ENROLLMENT_NOT_SUPPORTED",
                    "Secure card enrollment is not supported by this payment provider."
            );
        }
        return adapter;
    }

    private EnrollmentRow findEnrollment(UUID id, String accountHash, boolean forUpdate) {
        return DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, route_id, connection_id, network_id, account_reference_hash, provider_code,
                       provider_enrollment_reference, provider_customer_reference, gateway_payment_method_id,
                       status, currency, expires_at
                  FROM payment_gateway.gateway_payment_method_enrollment
                 WHERE id = ? AND account_reference_hash = ?
                """ + (forUpdate ? " FOR UPDATE" : ""),
                this::mapEnrollment,
                id,
                accountHash
        ));
    }

    private EnrollmentRow mapEnrollment(ResultSet rs, int rowNum) throws SQLException {
        return new EnrollmentRow(
                rs.getObject("id", UUID.class),
                rs.getObject("route_id", UUID.class),
                rs.getObject("connection_id", UUID.class),
                rs.getString("network_id"),
                rs.getString("account_reference_hash"),
                GatewayProvider.valueOf(rs.getString("provider_code")),
                rs.getString("provider_enrollment_reference"),
                rs.getString("provider_customer_reference"),
                rs.getObject("gateway_payment_method_id", UUID.class),
                PaymentMethodEnrollmentStatus.valueOf(rs.getString("status")),
                rs.getString("currency"),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant()
        );
    }

    private GatewayPaymentMethodRegistration requireRegistration(UUID id) {
        if (id == null) {
            throw new IllegalStateException("Completed enrollment has no payment method.");
        }
        GatewayPaymentMethodRegistration registration = DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, connection_id, provider_code, brand, last4, expiry_month, expiry_year, active, created_at
                  FROM payment_gateway.gateway_payment_method
                 WHERE id = ? AND active = TRUE
                """,
                (rs, rowNum) -> new GatewayPaymentMethodRegistration(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class),
                        GatewayProvider.valueOf(rs.getString("provider_code")),
                        rs.getString("brand"),
                        rs.getString("last4"),
                        rs.getInt("expiry_month"),
                        rs.getInt("expiry_year"),
                        rs.getBoolean("active"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()
                ),
                id
        ));
        if (registration == null) {
            throw new IllegalStateException("Completed enrollment payment method was not found.");
        }
        return registration;
    }

    private void updateStatus(UUID id, PaymentMethodEnrollmentStatus status, UUID paymentMethodId) {
        jdbcTemplate.update(
                """
                UPDATE payment_gateway.gateway_payment_method_enrollment
                   SET status = ?,
                       gateway_payment_method_id = COALESCE(?, gateway_payment_method_id),
                       completed_at = CASE WHEN ? = 'SUCCEEDED' THEN now() ELSE completed_at END,
                       updated_at = now()
                 WHERE id = ?
                """,
                status.name(), paymentMethodId, status.name(), id
        );
    }

    private GatewayPaymentMethodEnrollmentCompletion completed(
            EnrollmentRow enrollment,
            GatewayPaymentMethodRegistration registration
    ) {
        return new GatewayPaymentMethodEnrollmentCompletion(
                enrollment.id(),
                enrollment.routeId(),
                enrollment.networkId(),
                PaymentMethodEnrollmentStatus.SUCCEEDED,
                registration
        );
    }

    private void validateCard(ProviderEnrollmentResult result) {
        if (result.paymentMethodReference() == null
                || result.brand() == null || result.brand().isBlank()
                || result.last4() == null || !result.last4().matches("^\\d{4}$")
                || result.expiryMonth() < 1 || result.expiryMonth() > 12
                || result.expiryYear() < 0 || result.expiryYear() > 99) {
            throw new GatewayBusinessException("PAYMENT_METHOD_RESPONSE_INVALID", "The payment provider returned invalid card metadata.");
        }
    }

    private String validateReturnUrl(String value) {
        String normalized = value == null ? "" : value.trim();
        if (!normalized.matches("^electrahub://[A-Za-z0-9/_?=&.-]+$")
                && !normalized.matches("^https://[A-Za-z0-9._~:/?#@!$&'()*+,;=%-]+$")) {
            throw new GatewayBusinessException("PAYMENT_RETURN_URL_INVALID", "Payment return URL is invalid.");
        }
        return normalized;
    }

    private OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private record EnrollmentRow(
            UUID id,
            UUID routeId,
            UUID connectionId,
            String networkId,
            String accountHash,
            GatewayProvider provider,
            String providerEnrollmentReference,
            String providerCustomerReference,
            UUID paymentMethodId,
            PaymentMethodEnrollmentStatus status,
            String currency,
            Instant expiresAt
    ) {
    }
}
