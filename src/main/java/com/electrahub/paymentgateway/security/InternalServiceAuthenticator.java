package com.electrahub.paymentgateway.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Internal mutation endpoints require a service credential and must not be routed through public ingress. */
@Component
public class InternalServiceAuthenticator {

    public static final String HEADER = "X-ElectraHub-Internal-Key";
    private final byte[] expectedKey;

    public InternalServiceAuthenticator(
            @Value("${app.internal-service.key:${APP_INTERNAL_SERVICE_KEY:}}") String configuredKey,
            @Value("${spring.profiles.active:}") String activeProfiles
    ) {
        boolean missingKey = configuredKey == null || configuredKey.isBlank() || configuredKey.startsWith("CHANGE_ME");
        if (missingKey && activeProfiles != null && activeProfiles.toLowerCase().contains("prod")) {
            throw new IllegalStateException("APP_INTERNAL_SERVICE_KEY must be configured in production");
        }
        String resolved = missingKey ? "electrahub-local-internal-service-key" : configuredKey;
        this.expectedKey = resolved.getBytes(StandardCharsets.UTF_8);
    }

    public void require(HttpServletRequest request) {
        String supplied = request.getHeader(HEADER);
        if (supplied == null || !MessageDigest.isEqual(expectedKey, supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "An authenticated internal service credential is required.");
        }
    }
}
