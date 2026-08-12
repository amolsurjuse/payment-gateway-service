package com.electrahub.paymentgateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayCapability;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayEnvironment;
import com.electrahub.paymentgateway.domain.GatewayContracts.GatewayProvider;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GatewayRouteCacheTest {

    @Test
    void evictsAValueWhenAnotherPodAdvancesTheSharedGeneration() {
        MutableGeneration generation = new MutableGeneration(7L);
        GatewayRouteCache cache = new GatewayRouteCache(
                new GatewayProperties(Duration.ofMinutes(10), true, false), generation);
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        cache.put("US:USD:MOBILE:CARD", approvedResolution(), now);

        assertThat(cache.get("US:USD:MOBILE:CARD", now.plusSeconds(1))).isPresent();

        generation.value = 8L;

        assertThat(cache.get("US:USD:MOBILE:CARD", now.plusSeconds(2))).isEmpty();
    }

    @Test
    void bypassesTheLocalCacheWhenSharedGenerationCannotBeRead() {
        MutableGeneration generation = new MutableGeneration(3L);
        GatewayRouteCache cache = new GatewayRouteCache(
                new GatewayProperties(Duration.ofMinutes(10), true, false), generation);
        Instant now = Instant.parse("2026-07-21T12:00:00Z");
        cache.put("US:USD:MOBILE:CARD", approvedResolution(), now);

        generation.available = false;

        assertThat(cache.get("US:USD:MOBILE:CARD", now.plusSeconds(1))).isEmpty();
    }

    private RouteResolution approvedResolution() {
        return new RouteResolution(
                true,
                "APPROVED",
                "Route resolved.",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                GatewayProvider.STRIPE,
                GatewayEnvironment.SANDBOX,
                "EUR",
                1,
                1,
                Set.of(GatewayCapability.AUTHORIZE, GatewayCapability.STATUS_QUERY)
        );
    }

    private static final class MutableGeneration implements RouteCacheGeneration {
        private long value;
        private boolean available = true;

        private MutableGeneration(long value) {
            this.value = value;
        }

        @Override
        public OptionalLong current() {
            return available ? OptionalLong.of(value) : OptionalLong.empty();
        }

        @Override
        public void advance() {
            value++;
        }
    }
}
