package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.electrahub.paymentgateway.service.spi.ProviderCredentialResolver;
import org.springframework.core.env.Environment;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Resolves references such as {@code env:STRIPE_SANDBOX_CREDENTIAL}. Secret values remain in
 * process environment variables populated by Kubernetes Secrets and are never written to Git.
 */
@Component
public class EnvironmentProviderCredentialResolver implements ProviderCredentialResolver {

    private static final Pattern ENVIRONMENT_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{2,127}$");

    private final JdbcTemplate jdbcTemplate;
    private final Environment environment;

    public EnvironmentProviderCredentialResolver(JdbcTemplate jdbcTemplate, Environment environment) {
        this.jdbcTemplate = jdbcTemplate;
        this.environment = environment;
    }

    @Override
    public String requireCredential(GatewayConnection connection) {
        String reference = reference(connection, "credential_secret_reference");
        return resolve(reference).orElseThrow(() -> new GatewayBusinessException(
                "GATEWAY_CREDENTIAL_UNAVAILABLE",
                "The configured provider credential is unavailable."
        ));
    }

    @Override
    public Optional<String> webhookSecret(GatewayConnection connection) {
        return resolve(reference(connection, "webhook_secret_reference"));
    }

    private String reference(GatewayConnection connection, String column) {
        if (connection == null || connection.id() == null) {
            return null;
        }
        try {
            String reference = jdbcTemplate.queryForObject(
                    "SELECT " + column + " FROM payment_gateway.gateway_connection WHERE id = ?",
                    String.class,
                    connection.id()
            );
            ProviderSecretReferencePolicy.Purpose purpose = switch (column) {
                case "credential_secret_reference" -> ProviderSecretReferencePolicy.Purpose.CREDENTIAL;
                case "webhook_secret_reference" -> ProviderSecretReferencePolicy.Purpose.WEBHOOK;
                default -> throw new GatewayBusinessException(
                        "GATEWAY_SECRET_REFERENCE_INVALID", "The gateway secret purpose is invalid."
                );
            };
            return ProviderSecretReferencePolicy.requireApproved(
                    connection.provider(), connection.endpointProfile(), purpose, reference
            );
        } catch (EmptyResultDataAccessException exception) {
            throw new GatewayBusinessException("GATEWAY_CONNECTION_NOT_FOUND", "Gateway connection was not found.");
        }
    }

    private Optional<String> resolve(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String normalized = reference.trim();
        String environmentName = normalized.regionMatches(true, 0, "env:", 0, 4)
                ? normalized.substring(4).trim()
                : normalized;
        if (!ENVIRONMENT_NAME.matcher(environmentName).matches()) {
            throw new GatewayBusinessException(
                    "GATEWAY_SECRET_REFERENCE_INVALID",
                    "Gateway secret references must use env:ENVIRONMENT_VARIABLE_NAME."
            );
        }
        String value = environment.getProperty(environmentName);
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
    }
}
