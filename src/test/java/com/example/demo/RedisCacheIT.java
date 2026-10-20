package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import com.example.demo.config.CacheConfiguration;

class RedisCacheIT {
    private static LettuceConnectionFactory connection;
    private RedisCacheManager manager;
    private Cache cache;
    private final String key = "it_cache_" + UUID.randomUUID();

    @BeforeAll
    static void connect() {
        connection = new LettuceConnectionFactory("localhost", 6379);
        connection.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        connection.destroy();
    }

    @BeforeEach
    void setUp() {
        final CacheConfiguration configuration = new CacheConfiguration();
        final RedisCacheConfiguration defaults = configuration.defaultCacheConfiguration();
        manager = (RedisCacheManager) configuration.cacheManager(
                connection, defaults, configuration.pointsCacheConfiguration(defaults, Duration.ofMinutes(1)));
        manager.afterPropertiesSet();
        cache = manager.getCache("points");
    }

    @AfterEach
    void cleanup() {
        cache.evictIfPresent(key);
    }

    @Test
    void numericCacheValuesRoundTripAsLong() {
        cache.put(key, 100L);
        assertThat(cache.get(key, Long.class)).isEqualTo(100L);
    }

    @Test
    void evictionDoesNotWaitForInFlightDatabaseLoad() throws Exception {
        final CountDownLatch loadedOldValue = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        try (final ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<Long> reading = pool.submit(() -> cache.get(key, () -> {
                loadedOldValue.countDown();
                if (!releaseLoad.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test load timeout");
                }
                return 100L;
            }));
            assertThat(loadedOldValue.await(5, TimeUnit.SECONDS)).isTrue();
            final Future<?> eviction = pool.submit(() -> cache.evictIfPresent(key));
            try {
                eviction.get(1, TimeUnit.SECONDS);
            } finally {
                releaseLoad.countDown();
            }
            assertThat(reading.get(5, TimeUnit.SECONDS)).isEqualTo(100L);
            // With the default writer, an in-flight load can populate the cache after eviction.
            assertThat(cache.get(key, Long.class)).isEqualTo(100L);
        }
    }
}
