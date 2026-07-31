package com.electrahub.paymentgateway.service.twoc2p;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtHs256CodecTest {

    private final JwtHs256Codec codec = new JwtHs256Codec(new ObjectMapper());

    @Test
    void signsAndVerifiesPayloadWithoutPadding() {
        String token = codec.encode(Map.of("merchantID", "JT01", "invoiceNo", "123"), "sandbox-secret-key");

        assertThat(token.split("\\.")).hasSize(3);
        assertThat(codec.decodeAndVerify(token, "sandbox-secret-key").path("merchantID").asText())
                .isEqualTo("JT01");
    }

    @Test
    void rejectsTamperedSignature() {
        String token = codec.encode(Map.of("merchantID", "JT01"), "sandbox-secret-key");

        assertThatThrownBy(() -> codec.decodeAndVerify(token + "x", "sandbox-secret-key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("signature");
    }
}
