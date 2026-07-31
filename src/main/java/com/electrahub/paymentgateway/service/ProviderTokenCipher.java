package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Component
class ProviderTokenCipher {

    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final byte[] encryptionKey;
    private final SecureRandom secureRandom = new SecureRandom();

    ProviderTokenCipher(@Value("${app.gateway.token-vault.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.encryptionKey = null;
            return;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key.trim());
            if (decoded.length != 32) {
                throw new IllegalArgumentException("wrong key length");
            }
            this.encryptionKey = decoded;
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Gateway token-vault key must be a base64-encoded 256-bit key.", exception);
        }
    }

    String encrypt(String plaintext, String associatedData) {
        requireConfigured();
        try {
            byte[] nonce = new byte[NONCE_LENGTH];
            secureRandom.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array()
            );
        } catch (Exception exception) {
            throw new GatewayBusinessException("PAYMENT_METHOD_TOKEN_ENCRYPTION_FAILED", "Payment method token could not be protected.");
        }
    }

    String decrypt(String ciphertext, String associatedData) {
        requireConfigured();
        if (ciphertext == null || !ciphertext.startsWith("v1:")) {
            throw new GatewayBusinessException("PAYMENT_METHOD_TOKEN_VERSION_UNSUPPORTED", "Payment method token format is unsupported.");
        }
        try {
            byte[] stored = Base64.getDecoder().decode(ciphertext.substring(3));
            if (stored.length <= NONCE_LENGTH) {
                throw new IllegalArgumentException("ciphertext too short");
            }
            byte[] nonce = java.util.Arrays.copyOfRange(stored, 0, NONCE_LENGTH);
            byte[] encrypted = java.util.Arrays.copyOfRange(stored, NONCE_LENGTH, stored.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new GatewayBusinessException("PAYMENT_METHOD_TOKEN_DECRYPTION_FAILED", "Payment method token could not be opened.");
        }
    }

    boolean configured() {
        return encryptionKey != null;
    }

    private void requireConfigured() {
        if (encryptionKey == null) {
            throw new GatewayBusinessException(
                    "PAYMENT_METHOD_TOKEN_VAULT_NOT_CONFIGURED",
                    "Payment method token encryption is not configured."
            );
        }
    }
}
