package com.electrahub.paymentgateway.domain;

import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConfigurationSnapshot;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnection;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayConnectionStatus;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayConfigurationSnapshotTest {

    @Test
    void serializesConfigurationStateWithoutCredentialOrSecretReferences() throws Exception {
        GatewayConnection connection = new GatewayConnection(
                UUID.randomUUID(),
                GatewayProvider.TWO_C2P,
                GatewayEnvironment.SANDBOX,
                GatewayConnectionStatus.READY,
                "2c2p-v1",
                "sandbox-th",
                Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.REFUND),
                true,
                true,
                true,
                null,
                null,
                null,
                7,
                null,
                null
        );

        String json = new ObjectMapper().writeValueAsString(
                new GatewayConfigurationSnapshot(List.of(connection), List.of(), List.of())
        );

        assertThat(json)
                .contains("\"provider\":\"TWO_C2P\"")
                .contains("\"environment\":\"SANDBOX\"")
                .contains("\"credentialConfigured\":true")
                .contains("\"webhookSecretConfigured\":true")
                .contains("\"certificateConfigured\":true")
                .doesNotContain("credentialSecretReference")
                .doesNotContain("webhookSecretReference")
                .doesNotContain("certificateSecretReference")
                .doesNotContain("vault://");
    }
}
