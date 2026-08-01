package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;

final class ProviderSecretReferencePolicy {

    static final String TWO_C2P_DEMO_PROFILE = "2c2p-sandbox-sg-demo";

    private ProviderSecretReferencePolicy() {
    }

    static References requireApproved(
            GatewayProvider provider,
            String endpointProfile,
            String credentialReference,
            String webhookReference,
            String certificateReference
    ) {
        References references = new References(
                requireApproved(provider, endpointProfile, Purpose.CREDENTIAL, credentialReference),
                requireApproved(provider, endpointProfile, Purpose.WEBHOOK, webhookReference),
                requireApproved(provider, endpointProfile, Purpose.CERTIFICATE, certificateReference)
        );
        if (provider == GatewayProvider.TWO_C2P
                && TWO_C2P_DEMO_PROFILE.equalsIgnoreCase(normalizedEndpointProfile(endpointProfile))
                && references.credential() != null) {
            throw notApproved(provider);
        }
        return references;
    }

    static String requireApproved(
            GatewayProvider provider,
            String endpointProfile,
            Purpose purpose,
            String reference
    ) {
        String normalized = normalize(reference);
        if (normalized == null) {
            return null;
        }
        String expected = expected(provider, purpose);
        if (expected == null || !expected.equals(normalized)
                || (provider == GatewayProvider.TWO_C2P
                && purpose == Purpose.CREDENTIAL
                && TWO_C2P_DEMO_PROFILE.equalsIgnoreCase(normalizedEndpointProfile(endpointProfile)))) {
            throw notApproved(provider);
        }
        return normalized;
    }

    private static String expected(GatewayProvider provider, Purpose purpose) {
        return switch (purpose) {
            case CREDENTIAL -> switch (provider) {
                case STRIPE -> "env:APP_GATEWAY_STRIPE_CREDENTIAL";
                case MOLLIE -> "env:APP_GATEWAY_MOLLIE_CREDENTIAL";
                case RAZORPAY -> "env:APP_GATEWAY_RAZORPAY_CREDENTIAL";
                case ADYEN -> "env:APP_GATEWAY_ADYEN_CREDENTIAL";
                case TWO_C2P -> "env:APP_GATEWAY_2C2P_CREDENTIAL";
                case MOCK -> null;
            };
            case WEBHOOK -> switch (provider) {
                case STRIPE -> "env:APP_GATEWAY_STRIPE_WEBHOOK_SECRET";
                case RAZORPAY -> "env:APP_GATEWAY_RAZORPAY_WEBHOOK_SECRET";
                case ADYEN -> "env:APP_GATEWAY_ADYEN_WEBHOOK_SECRET";
                case MOCK, MOLLIE, TWO_C2P -> null;
            };
            case CERTIFICATE -> null;
        };
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim();
        if (normalized.length() > 256) {
            throw new GatewayBusinessException(
                    "INVALID_SECRET_REFERENCE",
                    "Gateway secret references exceed the allowed length."
            );
        }
        return normalized;
    }

    private static String normalizedEndpointProfile(String value) {
        return value == null ? "" : value.trim();
    }

    private static GatewayBusinessException notApproved(GatewayProvider provider) {
        return new GatewayBusinessException(
                "GATEWAY_SECRET_REFERENCE_NOT_APPROVED",
                "The secret reference is not approved for the selected " + provider.name() + " provider and purpose."
        );
    }

    enum Purpose {
        CREDENTIAL,
        WEBHOOK,
        CERTIFICATE
    }

    record References(String credential, String webhook, String certificate) {
    }
}
