package com.electrahub.paymentgateway.api;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolutionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ScopedRouteResolutionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.RegisterGatewayPaymentMethodRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodRegistration;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateGatewayPaymentMethodEnrollmentRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CompleteGatewayPaymentMethodEnrollmentRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodEnrollment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayPaymentMethodEnrollmentCompletion;
import com.electrahub.paymentgateway.security.InternalServiceAuthenticator;
import com.electrahub.paymentgateway.service.GatewayOperationService;
import com.electrahub.paymentgateway.service.PaymentRouteResolver;
import com.electrahub.paymentgateway.service.GatewayPaymentMethodVault;
import com.electrahub.paymentgateway.service.PaymentMethodEnrollmentService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gateway/internal")
public class GatewayInternalController {

    private final InternalServiceAuthenticator internalServiceAuthenticator;
    private final PaymentRouteResolver routeResolver;
    private final GatewayOperationService operationService;
    private final GatewayPaymentMethodVault paymentMethodVault;
    private final PaymentMethodEnrollmentService enrollmentService;

    public GatewayInternalController(
            InternalServiceAuthenticator internalServiceAuthenticator,
            PaymentRouteResolver routeResolver,
            GatewayOperationService operationService,
            GatewayPaymentMethodVault paymentMethodVault,
            PaymentMethodEnrollmentService enrollmentService
    ) {
        this.internalServiceAuthenticator = internalServiceAuthenticator;
        this.routeResolver = routeResolver;
        this.operationService = operationService;
        this.paymentMethodVault = paymentMethodVault;
        this.enrollmentService = enrollmentService;
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

    @GetMapping("/operations/{operationId}")
    public GatewayOperationResult operation(
            HttpServletRequest request,
            @PathVariable UUID operationId
    ) {
        internalServiceAuthenticator.require(request);
        GatewayOperationResult result = operationService.find(operationId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway operation not found.");
        }
        return result;
    }

    @PostMapping("/operations/{operationId}/refresh")
    public GatewayOperationResult refreshOperation(
            HttpServletRequest request,
            @PathVariable UUID operationId
    ) {
        internalServiceAuthenticator.require(request);
        GatewayOperationResult result = operationService.refresh(operationId);
        if (result == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Gateway operation not found.");
        }
        return result;
    }

    @PostMapping("/payment-method-enrollments")
    public GatewayPaymentMethodEnrollment startPaymentMethodEnrollment(
            HttpServletRequest request,
            @Valid @RequestBody CreateGatewayPaymentMethodEnrollmentRequest payload
    ) {
        internalServiceAuthenticator.require(request);
        return enrollmentService.start(payload);
    }

    @PostMapping("/payment-method-enrollments/{enrollmentId}/complete")
    public GatewayPaymentMethodEnrollmentCompletion completePaymentMethodEnrollment(
            HttpServletRequest request,
            @PathVariable UUID enrollmentId,
            @Valid @RequestBody CompleteGatewayPaymentMethodEnrollmentRequest payload
    ) {
        internalServiceAuthenticator.require(request);
        return enrollmentService.complete(enrollmentId, payload);
    }
    @PostMapping("/payment-methods")
    public GatewayPaymentMethodRegistration registerPaymentMethod(
            HttpServletRequest request,
            @Valid @RequestBody RegisterGatewayPaymentMethodRequest payload
    ) {
        internalServiceAuthenticator.require(request);
        return paymentMethodVault.register(payload);
    }
}
