package com.electrahub.paymentgateway.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Verifies the short-lived, gateway-signed admin context without trusting client supplied role headers. */
@Component
public class GatewayAdminAccessContextResolver {

    public static final String CONTEXT_HEADER = "X-ElectraHub-Access-Context";
    public static final String SIGNATURE_HEADER = "X-ElectraHub-Access-Context-Signature";
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ObjectMapper objectMapper;
    private final byte[] secret;

    public GatewayAdminAccessContextResolver(
            ObjectMapper objectMapper,
            @Value("${app.access-context.secret:${APP_INTERNAL_ACCESS_CONTEXT_SECRET:}}") String configuredSecret,
            @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        this.objectMapper = objectMapper;
        boolean missingSecret = configuredSecret == null || configuredSecret.isBlank() || configuredSecret.startsWith("CHANGE_ME");
        if (missingSecret && activeProfiles != null && activeProfiles.toLowerCase().contains("prod")) {
            throw new IllegalStateException("APP_INTERNAL_ACCESS_CONTEXT_SECRET must be configured in production");
        }
        String resolved = missingSecret ? "electrahub-local-access-context-secret" : configuredSecret;
        this.secret = resolved.getBytes(StandardCharsets.UTF_8);
    }

    public UUID requireSystemAdmin(HttpServletRequest request) {
        String encoded = request.getHeader(CONTEXT_HEADER);
        String signature = request.getHeader(SIGNATURE_HEADER);
        if (encoded == null || signature == null || !validSignature(encoded, signature)) {
            throw forbidden("A gateway-issued system-administrator context is required.");
        }
        try {
            Payload payload = objectMapper.readValue(Base64.getUrlDecoder().decode(encoded), Payload.class);
            if (!payload.systemAdmin() || payload.expiresAt() == null || payload.expiresAt() <= Instant.now().toEpochMilli()) {
                throw forbidden("System administrator access is required.");
            }
            return UUID.fromString(payload.actorId());
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw forbidden("The administrative access context is invalid.");
        }
    }

    private boolean validSignature(String payload, String suppliedSignature) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            byte[] expected = mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
            byte[] supplied = Base64.getUrlDecoder().decode(suppliedSignature);
            return MessageDigest.isEqual(expected, supplied);
        } catch (Exception ex) {
            return false;
        }
    }

    private ResponseStatusException forbidden(String message) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, message);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record Payload(String actorId, boolean systemAdmin, Long expiresAt) {
    }
}
