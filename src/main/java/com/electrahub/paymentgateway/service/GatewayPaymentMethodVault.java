package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodRegistration;
import com.electrahub.paymentgateway.domain.GatewayContracts.RegisterGatewayPaymentMethodRequest;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class GatewayPaymentMethodVault {

    private final JdbcTemplate jdbcTemplate;
    private final GatewayConfigurationService configurationService;
    private final ProviderTokenCipher tokenCipher;

    public GatewayPaymentMethodVault(
            JdbcTemplate jdbcTemplate,
            GatewayConfigurationService configurationService,
            ProviderTokenCipher tokenCipher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.configurationService = configurationService;
        this.tokenCipher = tokenCipher;
    }

    @Transactional
    public GatewayPaymentMethodRegistration register(RegisterGatewayPaymentMethodRequest request) {
        return register(request, null);
    }

    @Transactional
    public GatewayPaymentMethodRegistration register(
            RegisterGatewayPaymentMethodRequest request,
            String providerCustomerReference
    ) {
        GatewayConnection connection = configurationService.requireConnection(request.connectionId());
        if (connection.status() != GatewayConnectionStatus.ACTIVE) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_ACTIVE", "Payment methods require an active gateway connection.");
        }
        String providerToken = validateProviderToken(request.providerToken());
        String accountHash = GatewayAccountReferenceHasher.hash(request.accountReference());
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.now();
        String associatedData = associatedData(id, connection.id(), accountHash);
        String ciphertext = tokenCipher.encrypt(providerToken, associatedData);
        jdbcTemplate.update(
                """
                INSERT INTO payment_gateway.gateway_payment_method
                    (id, connection_id, account_reference_hash, provider_code, provider_token_ciphertext,
                     provider_customer_reference, brand, last4, expiry_month, expiry_year, active, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                id,
                connection.id(),
                accountHash,
                connection.provider().name(),
                ciphertext,
                blankToNull(providerCustomerReference),
                request.brand().trim(),
                request.last4(),
                request.expiryMonth(),
                request.expiryYear(),
                offset(createdAt),
                offset(createdAt)
        );
        return new GatewayPaymentMethodRegistration(
                id, connection.id(), connection.provider(), request.brand().trim(), request.last4(),
                request.expiryMonth(), request.expiryYear(), true, createdAt
        );
    }

    ResolvedGatewayPaymentMethod resolve(String reference, String accountReference, GatewayConnection connection) {
        UUID paymentMethodId = parseUuid(reference);
        if (paymentMethodId == null) {
            return new ResolvedGatewayPaymentMethod(reference, null);
        }
        if (accountReference == null || accountReference.isBlank()) {
            throw new GatewayBusinessException("PAYMENT_METHOD_ACCOUNT_REQUIRED", "Payment method account reference is required.");
        }
        String accountHash = GatewayAccountReferenceHasher.hash(accountReference);
        StoredPaymentMethod stored = DataAccessUtils.singleResult(jdbcTemplate.query(
                """
                SELECT id, connection_id, account_reference_hash, provider_token_ciphertext, provider_customer_reference
                  FROM payment_gateway.gateway_payment_method
                 WHERE id = ? AND connection_id = ? AND provider_code = ? AND account_reference_hash = ? AND active = TRUE
                """,
                (rs, rowNum) -> new StoredPaymentMethod(
                        rs.getObject("id", UUID.class),
                        rs.getObject("connection_id", UUID.class),
                        rs.getString("account_reference_hash"),
                        rs.getString("provider_token_ciphertext"),
                        rs.getString("provider_customer_reference")
                ),
                paymentMethodId,
                connection.id(),
                connection.provider().name(),
                accountHash
        ));
        if (stored == null) {
            throw new GatewayBusinessException("PAYMENT_METHOD_NOT_AVAILABLE", "Payment method is not available for this account and provider.");
        }
        return new ResolvedGatewayPaymentMethod(
                tokenCipher.decrypt(stored.ciphertext(), associatedData(stored.id(), stored.connectionId(), stored.accountHash())),
                stored.providerCustomerReference()
        );
    }

    private String validateProviderToken(String value) {
        String token = value == null ? "" : value.trim();
        if (token.length() < 6 || token.length() > 512 || token.chars().anyMatch(Character::isWhitespace)) {
            throw new GatewayBusinessException("PAYMENT_METHOD_TOKEN_INVALID", "Provider payment method token is invalid.");
        }
        if (token.matches("^\\d{13,19}$")) {
            throw new GatewayBusinessException("RAW_CARD_DATA_FORBIDDEN", "Raw card numbers are not accepted.");
        }
        return token;
    }

    private UUID parseUuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private String associatedData(UUID id, UUID connectionId, String accountHash) {
        return id + ":" + connectionId + ":" + accountHash;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private OffsetDateTime offset(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    record ResolvedGatewayPaymentMethod(String providerToken, String providerCustomerReference) {
    }

    private record StoredPaymentMethod(
            UUID id,
            UUID connectionId,
            String accountHash,
            String ciphertext,
            String providerCustomerReference
    ) {
    }
}
