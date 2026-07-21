package com.electrahub.paymentgateway.service;

import java.util.OptionalLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Uses one small Redis counter to make local route-cache entries coherent
 * across gateway replicas. A Redis problem makes callers bypass the cache,
 * so configuration changes never depend on a best-effort invalidation event.
 */
@Component
public class RedisRouteCacheGeneration implements RouteCacheGeneration {

    private static final Logger log = LoggerFactory.getLogger(RedisRouteCacheGeneration.class);

    private final StringRedisTemplate redisTemplate;
    private final String generationKey;

    public RedisRouteCacheGeneration(
            StringRedisTemplate redisTemplate,
            @Value("${app.gateway.route-cache-generation-key:payment-gateway:route-cache-generation:v1}") String generationKey
    ) {
        this.redisTemplate = redisTemplate;
        this.generationKey = generationKey;
    }

    @Override
    public OptionalLong current() {
        try {
            String value = redisTemplate.opsForValue().get(generationKey);
            if (value == null || value.isBlank()) {
                return OptionalLong.of(0L);
            }
            return OptionalLong.of(Long.parseLong(value));
        } catch (RuntimeException ex) {
            log.warn("Payment route cache generation is unavailable; bypassing local cache: {}", ex.getClass().getSimpleName());
            return OptionalLong.empty();
        }
    }

    @Override
    public void advance() {
        try {
            redisTemplate.opsForValue().increment(generationKey);
        } catch (RuntimeException ex) {
            // The caller has already evicted its own cache. Other replicas will
            // also bypass their cache when Redis is unavailable and read DB.
            log.error("Could not advance payment route cache generation: {}", ex.getClass().getSimpleName());
        }
    }
}
