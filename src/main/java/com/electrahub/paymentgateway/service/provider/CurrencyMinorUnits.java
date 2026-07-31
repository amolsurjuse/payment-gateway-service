package com.electrahub.paymentgateway.service.provider;

import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;

public final class CurrencyMinorUnits {

    private CurrencyMinorUnits() {
    }

    public static long toMinorUnits(BigDecimal amount, String currencyCode) {
        try {
            Currency currency = Currency.getInstance(currencyCode.toUpperCase(Locale.ROOT));
            int scale = Math.max(currency.getDefaultFractionDigits(), 0);
            return amount.movePointRight(scale).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (IllegalArgumentException | ArithmeticException exception) {
            throw new GatewayBusinessException(
                    "INVALID_PROVIDER_AMOUNT",
                    "The amount cannot be represented in the currency's smallest unit."
            );
        }
    }
}
