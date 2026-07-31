package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.PaymentRoute;
import com.electrahub.paymentgateway.service.spi.GatewayBusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GatewayConfigurationRouteSqlTest {

    @Test
    void qualifiesRouteColumnsWhenListingRoutes() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<PaymentRoute>>any()
        )).thenReturn(List.of());

        service(jdbcTemplate).listPaymentRoutes();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<PaymentRoute>>any()
        );
        assertThat(sql.getValue()).contains("ORDER BY r.priority ASC, r.created_at DESC");
    }

    @Test
    void qualifiesRouteIdWhenLoadingARoute() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        UUID routeId = UUID.randomUUID();
        when(jdbcTemplate.query(
                anyString(),
                org.mockito.ArgumentMatchers.<RowMapper<PaymentRoute>>any(),
                eq(routeId)
        )).thenReturn(List.of());

        assertThatThrownBy(() -> service(jdbcTemplate)
                .setPaymentRouteEnabled(routeId, false, UUID.randomUUID()))
                .isInstanceOf(GatewayBusinessException.class)
                .hasMessageContaining("Payment route was not found");

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).query(
                sql.capture(),
                org.mockito.ArgumentMatchers.<RowMapper<PaymentRoute>>any(),
                eq(routeId)
        );
        assertThat(sql.getValue()).contains("WHERE r.id = ?");
    }

    private GatewayConfigurationService service(JdbcTemplate jdbcTemplate) {
        return new GatewayConfigurationService(
                jdbcTemplate,
                mock(PaymentGatewayRegistry.class),
                new GatewayRouteCache(new GatewayProperties(Duration.ofMinutes(10), true, false))
        );
    }
}