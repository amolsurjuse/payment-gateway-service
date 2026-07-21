package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
public class PaymentRouteResolver {

    private final GatewayConfigurationService configurationService;
    private final GatewayRoutePolicy policy;
    private final GatewayRouteCache cache;

    public PaymentRouteResolver(
            GatewayConfigurationService configurationService,
            GatewayRoutePolicy policy,
            GatewayRouteCache cache
    ) {
        this.configurationService = configurationService;
        this.policy = policy;
        this.cache = cache;
    }

    public RouteResolution resolve(RouteResolutionRequest request) {
        Instant now = Instant.now();
        String key = cacheKey(request);
        return cache.get(key, now).orElseGet(() -> {
            List<GatewayRouteCandidate> candidates = configurationService.findCandidates(request);
            RouteResolution resolution = candidates.stream()
                    .map(candidate -> policy.evaluate(candidate.route(), candidate.connection(), request, now))
                    .filter(RouteResolution::approved)
                    .findFirst()
                    .orElseGet(() -> candidates.isEmpty()
                            ? RouteResolution.rejected("PAYMENT_ROUTE_NOT_CONFIGURED", "No matching payment route is configured.")
                            : RouteResolution.rejected("PAYMENT_METHOD_NOT_SUPPORTED", "Matching payment routes do not meet the required capabilities."));
            if (resolution.approved()) {
                cache.put(key, resolution, now);
            }
            return resolution;
        });
    }

    /**
     * Uses the server-owned session hierarchy. The selected route supplies the settlement
     * currency, so a mobile or web client cannot choose a settlement destination.
     */
    public RouteResolution resolve(ScopedRouteResolutionRequest request) {
        Instant now = Instant.now();
        String key = scopedCacheKey(request);
        return cache.get(key, now).orElseGet(() -> {
            List<GatewayRouteCandidate> candidates = configurationService.findCandidates(request);
            RouteResolution resolution = candidates.stream()
                    .map(candidate -> policy.evaluate(
                            candidate.route(),
                            candidate.connection(),
                            new RouteResolutionRequest(
                                    candidate.route().merchantAccountId(),
                                    request.chargingCountry(),
                                    request.presentmentCurrency(),
                                    candidate.route().settlementCurrency(),
                                    request.channel(),
                                    request.paymentMethod(),
                                    request.requiredCapabilities()
                            ),
                            now
                    ))
                    .filter(RouteResolution::approved)
                    .findFirst()
                    .orElseGet(() -> candidates.isEmpty()
                            ? RouteResolution.rejected("PAYMENT_ROUTE_NOT_CONFIGURED", "No payment route is configured for this network and charging currency.")
                            : RouteResolution.rejected("PAYMENT_METHOD_NOT_SUPPORTED", "Matching payment routes do not meet the required capabilities."));
            if (resolution.approved()) {
                cache.put(key, resolution, now);
            }
            return resolution;
        });
    }

    private String cacheKey(RouteResolutionRequest request) {
        return String.join(":",
                request.merchantAccountId().toString(),
                request.chargingCountry().trim().toUpperCase(),
                request.presentmentCurrency().trim().toUpperCase(),
                request.settlementCurrency().trim().toUpperCase(),
                request.channel().name(),
                request.paymentMethod().name(),
                request.requiredCapabilities().stream().map(Enum::name).sorted().reduce("", (left, right) -> left + "," + right)
        );
    }

    private String scopedCacheKey(ScopedRouteResolutionRequest request) {
        return String.join(":",
                "scope",
                request.enterpriseId().trim().toUpperCase(),
                request.networkId().trim().toUpperCase(),
                request.chargingCountry().trim().toUpperCase(),
                request.presentmentCurrency().trim().toUpperCase(),
                request.channel().name(),
                request.paymentMethod().name(),
                request.requiredCapabilities().stream().map(Enum::name).sorted().reduce("", (left, right) -> left + "," + right)
        );
    }
}
