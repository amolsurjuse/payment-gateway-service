package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderTokenCipherTest {

    @Test
    void encryptsWithRandomNonceAndBindsCiphertextToAssociatedData() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        ProviderTokenCipher cipher = new ProviderTokenCipher(key);

        String first = cipher.encrypt("pm_provider_token", "method:connection:account");
        String second = cipher.encrypt("pm_provider_token", "method:connection:account");

        assertThat(first).startsWith("v1:").isNotEqualTo(second).doesNotContain("pm_provider_token");
        assertThat(cipher.decrypt(first, "method:connection:account")).isEqualTo("pm_provider_token");
        assertThatThrownBy(() -> cipher.decrypt(first, "different-account"))
                .isInstanceOf(GatewayBusinessException.class);
    }

    @Test
    void remainsDormantWhenEncryptionKeyIsNotConfigured() {
        ProviderTokenCipher cipher = new ProviderTokenCipher("");

        assertThatThrownBy(() -> cipher.encrypt("pm_provider_token", "aad"))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("not configured");
    }
}
