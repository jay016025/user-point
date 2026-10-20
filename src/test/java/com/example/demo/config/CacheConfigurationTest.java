package com.example.demo.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheDecorator;
import org.springframework.data.redis.cache.RedisCache;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

import com.example.demo.service.PointsTotalReader;

class CacheConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(CacheConfiguration.class)
            .withBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));

    @Test
    void managerUsesPointsBeanWhenMultipleCacheConfigurationsExist() {
        final RedisCacheConfiguration jsonConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(String.class)));
        runner.withPropertyValues("points.cache-ttl=5m")
                .withBean("jsonCacheConfiguration", RedisCacheConfiguration.class, () -> jsonConfig)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    final RedisCacheConfiguration defaults =
                            context.getBean("defaultCacheConfiguration", RedisCacheConfiguration.class);
                    final RedisCacheConfiguration points =
                            context.getBean("pointsCacheConfiguration", RedisCacheConfiguration.class);
                    final CacheManager manager = context.getBean(CacheManager.class);
                    final TransactionAwareCacheDecorator cache =
                            (TransactionAwareCacheDecorator) manager.getCache(PointsTotalReader.CACHE);
                    assertThat(cache).isNotNull();
                    final RedisCache redisCache = (RedisCache) cache.getTargetCache();
                    assertThat(redisCache.getCacheConfiguration()).isSameAs(points);
                    assertThat(points.getTtlFunction().getTimeToLive("u001", 100L))
                            .isEqualTo(Duration.ofMinutes(5));
                    assertThat(points.getValueSerializationPair()
                                    .read(ByteBuffer.wrap("100".getBytes(StandardCharsets.UTF_8))))
                            .isEqualTo(100L);
                    assertThat(defaults.getTtlFunction().getTimeToLive("u001", 100L))
                            .isEqualTo(Duration.ZERO);
                });
    }
}
