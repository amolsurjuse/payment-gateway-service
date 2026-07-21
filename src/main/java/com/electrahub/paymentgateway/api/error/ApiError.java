package com.electrahub.paymentgateway.api.error;

import java.time.Instant;

public record ApiError(String code, String message, Instant timestamp) {
}
