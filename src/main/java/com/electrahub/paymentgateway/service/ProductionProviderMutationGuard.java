package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.springframework.stereotype.Component;

/**
 * Blocks new financial activity through real production providers when the deployment kill switch is off.
 * Read-only status reconciliation, webhook verification, and completion of an already-started enrollment remain
 * available so in-flight operations can be recovered safely while new routes and mutations are disabled.
 */
@Component
public class ProductionProviderMutationGuard {

    static final String ERROR_CODE = "PRODUCTION_PROVIDER_DISABLED";
    static final String ERROR_MESSAGE = "New production payment-provider operations are disabled in this deployment.";

    private final GatewayProperties properties;

    public ProductionProviderMutationGuard(GatewayProperties properties) {
        this.properties = properties;
    }

    public boolean isAllowed(GatewayConnection connection) {
        return connection.environment() != GatewayEnvironment.PRODUCTION
                || connection.provider() == GatewayProvider.MOCK
                || properties.productionEnabled();
    }

    public void requireAllowed(GatewayConnection connection) {
        if (!isAllowed(connection)) {
            throw new GatewayBusinessException(ERROR_CODE, ERROR_MESSAGE);
        }
    }
}
