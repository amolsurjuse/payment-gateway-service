package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderSecretReferencePolicyTest {

    @Test
    void acceptsOnlyCanonicalProviderAndPurposeReferences() {
        var references = ProviderSecretReferencePolicy.requireApproved(
                GatewayProvider.ADYEN,
                "adyen-sandbox",
                "env:APP_GATEWAY_ADYEN_CREDENTIAL",
                "env:APP_GATEWAY_ADYEN_WEBHOOK_SECRET",
                null
        );

        assertThat(references.credential()).isEqualTo("env:APP_GATEWAY_ADYEN_CREDENTIAL");
        assertThat(references.webhook()).isEqualTo("env:APP_GATEWAY_ADYEN_WEBHOOK_SECRET");
        assertThat(references.certificate()).isNull();
    }

    @Test
    void rejectsCrossProviderAndInternalEnvironmentReferences() {
        assertNotApproved(GatewayProvider.MOLLIE, "mollie-sandbox", "env:APP_GATEWAY_ADYEN_CREDENTIAL", null);
        assertNotApproved(GatewayProvider.RAZORPAY, "razorpay-sandbox", "env:APP_SECURITY_INTERNAL_TOKEN", null);
        assertNotApproved(GatewayProvider.STRIPE, "stripe-sandbox", "env:APP_GATEWAY_STRIPE_CREDENTIAL",
                "env:APP_GATEWAY_ADYEN_WEBHOOK_SECRET");
    }

    @Test
    void keepsMollieWebhookAndAllUnusedCertificateReferencesBlank() {
        assertNotApproved(GatewayProvider.MOLLIE, "mollie-sandbox", "env:APP_GATEWAY_MOLLIE_CREDENTIAL",
                "env:APP_GATEWAY_MOLLIE_WEBHOOK_SECRET");

        assertThatThrownBy(() -> ProviderSecretReferencePolicy.requireApproved(
                GatewayProvider.RAZORPAY,
                "razorpay-sandbox",
                "env:APP_GATEWAY_RAZORPAY_CREDENTIAL",
                "env:APP_GATEWAY_RAZORPAY_WEBHOOK_SECRET",
                "env:APP_GATEWAY_RAZORPAY_CERTIFICATE"
        )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("GATEWAY_SECRET_REFERENCE_NOT_APPROVED")
        );
    }

    @Test
    void requiresThePublicTwoC2PDemoCredentialReferenceToStayBlank() {
        assertThat(ProviderSecretReferencePolicy.requireApproved(
                GatewayProvider.TWO_C2P,
                ProviderSecretReferencePolicy.TWO_C2P_DEMO_PROFILE,
                null,
                null,
                null
        ).credential()).isNull();

        assertNotApproved(
                GatewayProvider.TWO_C2P,
                "  " + ProviderSecretReferencePolicy.TWO_C2P_DEMO_PROFILE + "  ",
                "env:APP_GATEWAY_2C2P_CREDENTIAL",
                null
        );
    }

    private void assertNotApproved(
            GatewayProvider provider,
            String endpointProfile,
            String credentialReference,
            String webhookReference
    ) {
        assertThatThrownBy(() -> ProviderSecretReferencePolicy.requireApproved(
                provider, endpointProfile, credentialReference, webhookReference, null
        )).isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                assertThat(exception.code()).isEqualTo("GATEWAY_SECRET_REFERENCE_NOT_APPROVED")
        );
    }
}
