package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayOperationServicePaymentReferenceTest {

    @Test
    void allowsOpaqueVaultAndProviderReferences() {
        assertThatCode(() -> GatewayOperationService.rejectRawPaymentData(
                "ef19e0c5-aaa2-46bd-830e-61dcbcde09f9"
        )).doesNotThrowAnyException();
        assertThatCode(() -> GatewayOperationService.rejectRawPaymentData(
                "pm_1RrxYzLkdIwHu7ixZ2K7PfMh"
        )).doesNotThrowAnyException();
    }

    @Test
    void rejectsRawCardNumbersWithOrWithoutFormatting() {
        assertRawCardRejected("4111111111111111");
        assertRawCardRejected("4111 1111 1111 1111");
        assertRawCardRejected("4111-1111-1111-1111");
    }

    private void assertRawCardRejected(String reference) {
        assertThatThrownBy(() -> GatewayOperationService.rejectRawPaymentData(reference))
                .isInstanceOfSatisfying(GatewayBusinessException.class, exception ->
                        assertThat(exception.code()).isEqualTo("RAW_PAYMENT_DATA_FORBIDDEN")
                );
    }
}
