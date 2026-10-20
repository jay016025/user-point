package com.example.demo.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericToStringSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.example.demo.service.PointsTotalReader;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfiguration implements CachingConfigurer {
    @Bean
    public RedisCacheConfiguration defaultCacheConfiguration() {
        return RedisCacheConfiguration.defaultCacheConfig().disableCachingNullValues();
    }

    @Bean
    public RedisCacheConfiguration pointsCacheConfiguration(
            @Qualifier("defaultCacheConfiguration") final RedisCacheConfiguration defaults,
            @Value("${points.cache-ttl:60s}") final Duration ttl) {
        return defaults.entryTtl(ttl)
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new GenericToStringSerializer<>(Long.class)));
    }

    @Bean
    public CacheManager cacheManager(
            final RedisConnectionFactory factory,
            @Qualifier("defaultCacheConfiguration") final RedisCacheConfiguration defaults,
            @Qualifier("pointsCacheConfiguration") final RedisCacheConfiguration pointsConfig) {
        // Cache advice surrounds the transaction, so write eviction runs only after a successful commit.
        return RedisCacheManager.builder(factory)
                .cacheDefaults(defaults)
                .withInitialCacheConfigurations(Map.of(PointsTotalReader.CACHE, pointsConfig))
                .disableCreateOnMissingCache()
                .transactionAware()
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(final RuntimeException e, final Cache cache, final Object key) {
                log.warn("Cache read failed; falling back to MySQL", e);
            }

            @Override
            public void handleCachePutError(
                    final RuntimeException e, final Cache cache, final Object key, final Object value) {
                log.warn("Cache population failed; MySQL result remains usable", e);
            }

            @Override
            public void handleCacheEvictError(final RuntimeException e, final Cache cache, final Object key) {
                log.warn("Cache eviction failed; durable outbox will retry", e);
            }

            @Override
            public void handleCacheClearError(final RuntimeException e, final Cache cache) {
                log.warn("Cache clear failed", e);
            }
        };
    }
}
