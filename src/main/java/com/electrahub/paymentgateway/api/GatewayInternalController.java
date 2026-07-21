package com.electrahub.paymentgateway.api;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import com.electrahub.paymentgateway.security.InternalServiceAuthenticator;
import com.electrahub.paymentgateway.service.GatewayOperationService;
import com.electrahub.paymentgateway.service.PaymentRouteResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/gateway/internal")
public class GatewayInternalController {

    private final InternalServiceAuthenticator internalServiceAuthenticator;
    private final PaymentRouteResolver routeResolver;
    private final GatewayOperationService operationService;

    public GatewayInternalController(
            InternalServiceAuthenticator internalServiceAuthenticator,
            PaymentRouteResolver routeResolver,
            GatewayOperationService operationService
    ) {
        this.internalServiceAuthenticator = internalServiceAuthenticator;
        this.routeResolver = routeResolver;
        this.operationService = operationService;
    }

    @PostMapping("/routes/resolve")
    public RouteResolution resolve(HttpServletRequest request, @Valid @RequestBody RouteResolutionRequest payload) {
        internalServiceAuthenticator.require(request);
        return routeResolver.resolve(payload);
    }

    @PostMapping("/routes/resolve-by-scope")
    public RouteResolution resolveByScope(HttpServletRequest request, @Valid @RequestBody ScopedRouteResolutionRequest payload) {
        internalServiceAuthenticator.require(request);
        return routeResolver.resolve(payload);
    }

    @PostMapping("/operations")
    public GatewayOperationResult execute(HttpServletRequest request, @Valid @RequestBody GatewayOperationRequest payload) {
        internalServiceAuthenticator.require(request);
        return operationService.execute(payload);
    }
}
