package com.electrahub.paymentgateway.service.spi;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;

import java.time.Instant;
import java.util.UUID;

/** Provider adapter for reusable, customer-bound payment method enrollment. */
public interface PaymentMethodEnrollmentProvider {

    GatewayProvider provider();

    ProviderCustomer createCustomer(GatewayConnection connection, String accountReferenceHash);

    ProviderEnrollment start(
            GatewayConnection connection,
            String providerCustomerReference,
            UUID enrollmentId,
            String countryCode,
            String currency,
            String returnUrl
    );

    ProviderEnrollmentResult retrieve(
            GatewayConnection connection,
            String providerEnrollmentReference,
            String providerResult
    );

    record ProviderCustomer(String reference) {
    }

    record ProviderEnrollment(
            String reference,
            String clientSecret,
            String publishableKey,
            Instant expiresAt
    ) {
    }

    record ProviderEnrollmentResult(
            String status,
            String paymentMethodReference,
            String providerCustomerReference,
            String brand,
            String last4,
            int expiryMonth,
            int expiryYear
    ) {
    }
}
