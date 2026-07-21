package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.PaymentGatewayAdapter;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PaymentGatewayRegistry {

    private final Map<GatewayProvider, PaymentGatewayAdapter> adapters;

    public PaymentGatewayRegistry(List<PaymentGatewayAdapter> adapters) {
        EnumMap<GatewayProvider, PaymentGatewayAdapter> configured = new EnumMap<>(GatewayProvider.class);
        for (PaymentGatewayAdapter adapter : adapters) {
            PaymentGatewayAdapter previous = configured.putIfAbsent(adapter.provider(), adapter);
            if (previous != null) {
                throw new IllegalStateException("More than one adapter is registered for " + adapter.provider());
            }
        }
        this.adapters = Map.copyOf(configured);
    }

    public Optional<PaymentGatewayAdapter> find(GatewayProvider provider) {
        return Optional.ofNullable(adapters.get(provider));
    }
}
