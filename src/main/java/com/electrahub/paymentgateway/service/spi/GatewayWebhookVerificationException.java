package com.electrahub.paymentgateway.service.spi;

public class GatewayWebhookVerificationException extends RuntimeException {

    private final String code;

    public GatewayWebhookVerificationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
