package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Component
public class GatewayRoutePolicy {

    public RouteResolution evaluate(PaymentRoute route, GatewayConnection connection, RouteResolutionRequest request, Instant now) {
        if (!route.enabled()) {
            return RouteResolution.rejected("PAYMENT_ROUTE_DISABLED", "The matching payment route is disabled.");
        }
        if (connection.status() != GatewayConnectionStatus.ACTIVE) {
            return RouteResolution.rejected("PAYMENT_ROUTE_NOT_CONFIGURED", "The payment route connection is not active.");
        }
        if (connection.provider() == GatewayProvider.MOCK && connection.environment() == GatewayEnvironment.PRODUCTION) {
            return RouteResolution.rejected("PAYMENT_ROUTE_DISABLED", "Mock routing is prohibited in production.");
        }
        if (route.effectiveFrom() != null && route.effectiveFrom().isAfter(now)
                || route.effectiveTo() != null && !route.effectiveTo().isAfter(now)) {
            return RouteResolution.rejected("PAYMENT_ROUTE_NOT_CONFIGURED", "The payment route is outside its effective period.");
        }
        if (!route.chargingCountry().equalsIgnoreCase(request.chargingCountry())
                || !route.presentmentCurrency().equalsIgnoreCase(request.presentmentCurrency())
                || !route.settlementCurrency().equalsIgnoreCase(request.settlementCurrency())
                || route.channel() != request.channel()
                || route.paymentMethod() != request.paymentMethod()) {
            return RouteResolution.rejected("PAYMENT_ROUTE_NOT_CONFIGURED", "No compatible payment route is configured.");
        }
        Set<GatewayCapability> required = new HashSet<>(route.requiredCapabilities());
        required.addAll(request.requiredCapabilities());
        if (!connection.capabilities().containsAll(required)) {
            return RouteResolution.rejected("PAYMENT_METHOD_NOT_SUPPORTED", "The configured payment route lacks a required capability.");
        }
        return new RouteResolution(
                true,
                "APPROVED",
                "A compatible payment route was resolved.",
                route.id(),
                connection.id(),
                connection.provider(),
                connection.environment(),
                route.configurationVersion(),
                connection.capabilities()
        );
    }
}
