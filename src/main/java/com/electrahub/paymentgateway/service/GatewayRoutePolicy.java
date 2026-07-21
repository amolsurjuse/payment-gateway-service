package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationType;
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

    /**
     * Authorizations are admitted only through an enabled, currently active route.  Once an
     * authorization exists, however, its capture, void, refund, and status inquiry must remain
     * possible even after an administrator disables the route for new business.  Otherwise an
     * emergency configuration change could strand an existing card hold or prevent a refund.
     */
    public RouteResolution evaluateOperation(
            PaymentRoute route,
            GatewayConnection connection,
            GatewayOperationType operationType,
            Instant now
    ) {
        Set<GatewayCapability> operationCapabilities = requiredCapabilities(operationType);
        if (operationType == GatewayOperationType.AUTHORIZE) {
            return evaluate(
                    route,
                    connection,
                    new RouteResolutionRequest(
                            route.merchantAccountId(),
                            route.chargingCountry(),
                            route.presentmentCurrency(),
                            route.settlementCurrency(),
                            route.channel(),
                            route.paymentMethod(),
                            operationCapabilities
                    ),
                    now
            );
        }

        if (connection.status() != GatewayConnectionStatus.ACTIVE
                && connection.status() != GatewayConnectionStatus.DISABLED) {
            return RouteResolution.rejected(
                    "PAYMENT_ROUTE_NOT_CONFIGURED",
                    "The payment connection is unavailable for lifecycle settlement operations."
            );
        }
        if (connection.provider() == GatewayProvider.MOCK && connection.environment() == GatewayEnvironment.PRODUCTION) {
            return RouteResolution.rejected("PAYMENT_ROUTE_DISABLED", "Mock routing is prohibited in production.");
        }

        Set<GatewayCapability> required = new HashSet<>(route.requiredCapabilities());
        required.addAll(operationCapabilities);
        if (!connection.capabilities().containsAll(required)) {
            return RouteResolution.rejected("PAYMENT_METHOD_NOT_SUPPORTED", "The configured payment route lacks a required capability.");
        }
        return approved(route, connection);
    }

    private Set<GatewayCapability> requiredCapabilities(GatewayOperationType operationType) {
        return switch (operationType) {
            case AUTHORIZE -> Set.of(GatewayCapability.AUTHORIZE);
            case VOID -> Set.of(GatewayCapability.VOID);
            case CAPTURE -> Set.of(GatewayCapability.CAPTURE);
            case REFUND -> Set.of(GatewayCapability.REFUND);
            case STATUS_QUERY -> Set.of(GatewayCapability.STATUS_QUERY);
        };
    }

    private RouteResolution approved(PaymentRoute route, GatewayConnection connection) {
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
