package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.CreatePaymentRouteRequest;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentChannel;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentMethodType;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GatewayConfigurationServiceTest {

    @Test
    void requiresASeparateAuditedActionToEnableANewRoute() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        GatewayConfigurationService service = new GatewayConfigurationService(
                jdbcTemplate,
                mock(PaymentGatewayRegistry.class),
                new GatewayRouteCache(new GatewayProperties(Duration.ofMinutes(10), true, false))
        );
        CreatePaymentRouteRequest request = new CreatePaymentRouteRequest(
                UUID.randomUUID(),
                "US",
                "USD",
                "USD",
                PaymentChannel.MOBILE,
                PaymentMethodType.CARD_ON_FILE,
                100,
                true,
                Set.of(),
                null,
                null
        );

        assertThatThrownBy(() -> service.createPaymentRoute(request, UUID.randomUUID()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("Create payment routes disabled");
        verifyNoInteractions(jdbcTemplate);
    }
}
