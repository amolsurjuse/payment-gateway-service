package com.electrahub.paymentgateway.service.spi;

import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;

/** Keeps provider-specific API names, SDKs, and transport behavior out of the financial domain. */
public interface PaymentGatewayAdapter {

    GatewayProvider provider();

    ConnectionValidation validate(GatewayConnection connection);

    GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection);

    GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection);
}
