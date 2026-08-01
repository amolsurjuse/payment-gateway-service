package com.electrahub.paymentgateway.service.twoc2p;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

final class JwtHs256Codec {

    private static final String HEADER = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";

    private final ObjectMapper objectMapper;

    JwtHs256Codec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    String encode(Object payload, String secretKey) {
        try {
            String header = base64Url(HEADER.getBytes(StandardCharsets.UTF_8));
            String body = base64Url(objectMapper.writeValueAsBytes(payload));
            String signingInput = header + "." + body;
            return signingInput + "." + base64Url(hmac(signingInput, secretKey));
        } catch (JsonProcessingException exception) {
            throw new GatewayBusinessException("TWO_C2P_REQUEST_INVALID", "Unable to create the 2C2P request.");
        }
    }

    JsonNode decodeAndVerify(String token, String secretKey) {
        String[] segments = token == null ? new String[0] : token.split("\\.");
        if (segments.length != 3) {
            throw invalidResponse();
        }
        try {
            JsonNode header = objectMapper.readTree(Base64.getUrlDecoder().decode(segments[0]));
            if (!"HS256".equals(header.path("alg").asText())) {
                throw invalidResponse();
            }
        } catch (IllegalArgumentException | IOException exception) {
            throw invalidResponse();
        }
        String signingInput = segments[0] + "." + segments[1];
        byte[] expected = hmac(signingInput, secretKey);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(segments[2]);
        } catch (IllegalArgumentException exception) {
            throw invalidResponse();
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new GatewayBusinessException("TWO_C2P_SIGNATURE_INVALID", "2C2P response signature is invalid.");
        }
        try {
            return objectMapper.readTree(Base64.getUrlDecoder().decode(segments[1]));
        } catch (IllegalArgumentException | IOException exception) {
            throw invalidResponse();
        }
    }

    private byte[] hmac(String value, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new GatewayBusinessException("TWO_C2P_SIGNATURE_FAILED", "Unable to sign the 2C2P request.");
        }
    }

    private String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private GatewayBusinessException invalidResponse() {
        return new GatewayBusinessException("TWO_C2P_RESPONSE_INVALID", "2C2P returned an invalid signed response.");
    }
}
