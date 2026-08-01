package com.electrahub.paymentgateway.api;

import com.electrahub.paymentgateway.domain.GatewayContracts.CreateGatewayConnectionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreateMerchantAccountRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreatePaymentRouteRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConfigurationSnapshot;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.MerchantAccount;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.domain.GatewayContracts.UpdateGatewayConnectionRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.UpdateMerchantAccountRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.UpdatePaymentRouteRequest;
import com.electrahub.paymentgateway.security.GatewayAdminAccessContextResolver;
import com.electrahub.paymentgateway.service.GatewayConfigurationService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PutMapping;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/gateway/admin")
public class GatewayAdminController {

    private final GatewayAdminAccessContextResolver accessContextResolver;
    private final GatewayConfigurationService configurationService;

    public GatewayAdminController(
            GatewayAdminAccessContextResolver accessContextResolver,
            GatewayConfigurationService configurationService
    ) {
        this.accessContextResolver = accessContextResolver;
        this.configurationService = configurationService;
    }

    @GetMapping("/configuration")
    public GatewayConfigurationSnapshot configuration(HttpServletRequest request) {
        accessContextResolver.requireSystemAdmin(request);
        return configurationService.configurationSnapshot();
    }

    @GetMapping("/connections")
    public List<GatewayConnection> connections(HttpServletRequest request) {
        accessContextResolver.requireSystemAdmin(request);
        return configurationService.listConnections();
    }

    @PostMapping("/connections")
    @ResponseStatus(HttpStatus.CREATED)
    public GatewayConnection createConnection(HttpServletRequest request, @Valid @RequestBody CreateGatewayConnectionRequest payload) {
        return configurationService.createConnection(payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PutMapping("/connections/{connectionId}")
    public GatewayConnection updateConnection(
            HttpServletRequest request,
            @PathVariable UUID connectionId,
            @Valid @RequestBody UpdateGatewayConnectionRequest payload
    ) {
        return configurationService.updateConnection(connectionId, payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/connections/{connectionId}/validate")
    public GatewayConnection validateConnection(HttpServletRequest request, @PathVariable UUID connectionId) {
        return configurationService.validateConnection(connectionId, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/connections/{connectionId}/probe")
    public ConnectionValidation probeConnection(HttpServletRequest request, @PathVariable UUID connectionId) {
        return configurationService.probeConnection(connectionId, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/connections/{connectionId}/activate")
    public GatewayConnection activateConnection(HttpServletRequest request, @PathVariable UUID connectionId) {
        return configurationService.activateConnection(connectionId, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/connections/{connectionId}/disable")
    public GatewayConnection disableConnection(HttpServletRequest request, @PathVariable UUID connectionId) {
        return configurationService.disableConnection(connectionId, accessContextResolver.requireSystemAdmin(request));
    }

    @GetMapping("/merchant-accounts")
    public List<MerchantAccount> merchantAccounts(HttpServletRequest request) {
        accessContextResolver.requireSystemAdmin(request);
        return configurationService.listMerchantAccounts();
    }

    @PostMapping("/merchant-accounts")
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantAccount createMerchantAccount(HttpServletRequest request, @Valid @RequestBody CreateMerchantAccountRequest payload) {
        return configurationService.createMerchantAccount(payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PutMapping("/merchant-accounts/{merchantAccountId}")
    public MerchantAccount updateMerchantAccount(
            HttpServletRequest request,
            @PathVariable UUID merchantAccountId,
            @Valid @RequestBody UpdateMerchantAccountRequest payload
    ) {
        return configurationService.updateMerchantAccount(merchantAccountId, payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/merchant-accounts/{merchantAccountId}/activate")
    public MerchantAccount activateMerchantAccount(HttpServletRequest request, @PathVariable UUID merchantAccountId) {
        return configurationService.activateMerchantAccount(merchantAccountId, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/merchant-accounts/{merchantAccountId}/disable")
    public MerchantAccount disableMerchantAccount(HttpServletRequest request, @PathVariable UUID merchantAccountId) {
        return configurationService.disableMerchantAccount(merchantAccountId, accessContextResolver.requireSystemAdmin(request));
    }

    @GetMapping("/routes")
    public List<PaymentRoute> routes(HttpServletRequest request) {
        accessContextResolver.requireSystemAdmin(request);
        return configurationService.listPaymentRoutes();
    }

    @PostMapping("/routes")
    @ResponseStatus(HttpStatus.CREATED)
    public PaymentRoute createRoute(HttpServletRequest request, @Valid @RequestBody CreatePaymentRouteRequest payload) {
        return configurationService.createPaymentRoute(payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PutMapping("/routes/{routeId}")
    public PaymentRoute updateRoute(
            HttpServletRequest request,
            @PathVariable UUID routeId,
            @Valid @RequestBody UpdatePaymentRouteRequest payload
    ) {
        return configurationService.updatePaymentRoute(routeId, payload, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/routes/{routeId}/enable")
    public PaymentRoute enableRoute(HttpServletRequest request, @PathVariable UUID routeId) {
        return configurationService.setPaymentRouteEnabled(routeId, true, accessContextResolver.requireSystemAdmin(request));
    }

    @PostMapping("/routes/{routeId}/disable")
    public PaymentRoute disableRoute(HttpServletRequest request, @PathVariable UUID routeId) {
        return configurationService.setPaymentRouteEnabled(routeId, false, accessContextResolver.requireSystemAdmin(request));
    }
}
