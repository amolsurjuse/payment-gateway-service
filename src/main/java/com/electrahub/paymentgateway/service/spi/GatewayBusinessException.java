package com.electrahub.paymentgateway.service.spi;

public class GatewayBusinessException extends RuntimeException {
    private final String code;

    public GatewayBusinessException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
