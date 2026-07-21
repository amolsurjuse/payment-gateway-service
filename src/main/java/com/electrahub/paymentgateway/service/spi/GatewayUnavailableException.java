package com.electrahub.paymentgateway.service.spi;

public class GatewayUnavailableException extends RuntimeException {
    private final String code;

    public GatewayUnavailableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
