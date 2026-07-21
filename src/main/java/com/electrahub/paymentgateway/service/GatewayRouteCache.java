package com.electrahub.paymentgateway.service;

import com.electrahub.paymentgateway.config.GatewayProperties;
import com.electrahub.paymentgateway.domain.GatewayContracts.RouteResolution;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * A bounded local hot-path cache verified against a Redis-backed generation.
 *
 * <p>Each resolution requires a small Redis generation read. It avoids a
 * heavier SQL join on a cache hit while guaranteeing a route change in one
 * pod invalidates entries in every other pod. If Redis is unavailable, the
 * cache is bypassed and the caller reads durable configuration instead.</p>
 */
@Component
public class GatewayRouteCache {

    private final GatewayProperties properties;
    private final RouteCacheGeneration generation;
    private final Map<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @Autowired
    public GatewayRouteCache(GatewayProperties properties, RouteCacheGeneration generation) {
        this.properties = properties;
        this.generation = generation;
    }

    /** Keeps narrow unit tests independent of a Redis server. */
    GatewayRouteCache(GatewayProperties properties) {
        this(properties, new LocalRouteCacheGeneration());
    }

    public Optional<RouteResolution> get(String key, Instant now) {
        OptionalLong currentGeneration = generation.current();
        if (currentGeneration.isEmpty()) {
            return Optional.empty();
        }
        CacheEntry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (entry.generation() != currentGeneration.getAsLong() || !entry.expiresAt().isAfter(now)) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.resolution());
    }

    public void put(String key, RouteResolution resolution, Instant now) {
        OptionalLong currentGeneration = generation.current();
        if (currentGeneration.isEmpty()) {
            entries.remove(key);
            return;
        }
        entries.put(key, new CacheEntry(resolution, now.plus(properties.routeCacheTtl()), currentGeneration.getAsLong()));
    }

    public void invalidateAll() {
        entries.clear();
        generation.advance();
    }

    private record CacheEntry(RouteResolution resolution, Instant expiresAt, long generation) {
    }

    private static final class LocalRouteCacheGeneration implements RouteCacheGeneration {
        private final AtomicLong value = new AtomicLong();

        @Override
        public OptionalLong current() {
            return OptionalLong.of(value.get());
        }

        @Override
        public void advance() {
            value.incrementAndGet();
        }
    }
}
