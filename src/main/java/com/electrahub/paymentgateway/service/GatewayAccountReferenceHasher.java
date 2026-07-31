package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

final class GatewayAccountReferenceHasher {

    private GatewayAccountReferenceHasher() {
    }

    static String hash(String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new GatewayBusinessException(
                    "PAYMENT_METHOD_ACCOUNT_REQUIRED",
                    "Payment method account reference is required."
            );
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }
}