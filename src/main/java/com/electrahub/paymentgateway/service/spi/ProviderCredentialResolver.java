package com.electrahub.paymentgateway.service.spi;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;

import java.util.Optional;

/** Resolves write-only references without exposing either references or values through API DTOs. */
public interface ProviderCredentialResolver {

    String requireCredential(GatewayConnection connection);

    Optional<String> webhookSecret(GatewayConnection connection);
}
