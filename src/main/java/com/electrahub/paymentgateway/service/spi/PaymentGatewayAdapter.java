package com.electrahub.paymentgateway.service.spi;

import com.electrahub.paymentgateway.domain.GatewayContracts.ConnectionValidation;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationResult;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayOperationStatusQuery;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayWebhookEvent;

import java.util.Map;
import java.util.List;

/** Keeps provider-specific API names, SDKs, and transport behavior out of the financial domain. */
public interface PaymentGatewayAdapter {

    GatewayProvider provider();

    default boolean requiresCredential(GatewayConnection connection) {
        return true;
    }

    ConnectionValidation validate(GatewayConnection connection);

    GatewayOperationResult execute(GatewayOperationRequest request, GatewayConnection connection);

    GatewayOperationResult queryStatus(GatewayOperationStatusQuery query, GatewayConnection connection);

    default GatewayWebhookEvent parseWebhook(
            GatewayConnection connection,
            String rawBody,
            Map<String, String> headers
    ) {
        throw new GatewayBusinessException("GATEWAY_WEBHOOK_UNSUPPORTED", "This provider does not support webhooks.");
    }

    default List<GatewayWebhookEvent> parseWebhooks(
            GatewayConnection connection,
            String rawBody,
            Map<String, String> headers
    ) {
        return List.of(parseWebhook(connection, rawBody, headers));
    }
}
