package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.persistence.leaderboard.RedisLeaderboardRepository;
import com.example.demo.persistence.mapper.LeaderboardRedisMapper;

class RedisLeaderboardIT {
    private static LettuceConnectionFactory connection;
    private StringRedisTemplate redis;
    private RedisLeaderboardRepository leaderboard;
    private final String prefix = "it_" + UUID.randomUUID() + "_";

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
        redis = new StringRedisTemplate(connection);
        leaderboard = new RedisLeaderboardRepository(redis, Mappers.getMapper(LeaderboardRedisMapper.class));
    }

    @AfterEach
    void cleanup() {
        for (int i = 0; i < 12; i++) {
            redis.opsForZSet().remove("points:{leaderboard}:scores", prefix + i);
            redis.opsForHash().delete("points:{leaderboard}:versions", prefix + i);
        }
    }

    private PointsChanged event(final int user, final long total, final long version) {
        return new PointsChanged(
                UUID.randomUUID().toString(),
                PointsEventType.POINTS_ADDED,
                prefix + user,
                1,
                total,
                total,
                version,
                "test",
                Instant.now());
    }

    @Test
    void returnsAtMostTenInDescendingScoreOrder() {
        for (int i = 0; i < 12; i++) {
            leaderboard.apply(event(i, 9000000000000000L + i, 1));
        }
        assertThat(leaderboard.topTen()).hasSize(10);
        assertThat(leaderboard.topTen())
                .extracting(PointsAccount::userId)
                .containsExactly(
                        prefix + 11,
                        prefix + 10,
                        prefix + 9,
                        prefix + 8,
                        prefix + 7,
                        prefix + 6,
                        prefix + 5,
                        prefix + 4,
                        prefix + 3,
                        prefix + 2);
    }

    @Test
    void staleAndDuplicateEventsDoNotOverwriteNewerScore() {
        leaderboard.apply(event(0, 9000000000000050L, 3));
        leaderboard.apply(event(0, 9000000000000020L, 2));
        leaderboard.apply(event(0, 9000000000000050L, 3));
        assertThat(leaderboard.topTen()).anySatisfy(a -> {
            assertThat(a.userId()).isEqualTo(prefix + 0);
            assertThat(a.total()).isEqualTo(9000000000000050L);
        });
    }

    @Test
    void deletionTombstonePreventsResurrectionButAllowsNewerAddition() {
        leaderboard.apply(event(0, 9000000000000050L, 1));
        leaderboard.remove(new PointsAccount(prefix + 0, 0, 2));
        leaderboard.apply(event(0, 9000000000000050L, 1));
        assertThat(leaderboard.topTen()).extracting(PointsAccount::userId).doesNotContain(prefix + 0);
        leaderboard.apply(event(0, 9000000000000070L, 3));
        assertThat(leaderboard.topTen()).extracting(PointsAccount::userId).contains(prefix + 0);
    }

    @Test
    void delayedDeletionDoesNotRemoveNewerAddition() {
        leaderboard.apply(event(0, 9000000000000050L, 3));
        leaderboard.remove(new PointsAccount(prefix + 0, 0, 2));
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, prefix + 0))
                .isEqualTo(9000000000000050.0);
    }

    @Test
    void snapshotRestoresScoresWhenVersionMarkersAlreadyExist() {
        redis.opsForHash()
                .put(RedisLeaderboardRepository.VERSIONS, prefix + 0, String.format(Locale.ROOT, "%019d", 2L));
        final List<PointsAccount> snapshot = List.of(new PointsAccount(prefix + 0, 9000000000000050L, 2));
        leaderboard.restore(snapshot);
        leaderboard.restore(snapshot);
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, prefix + 0))
                .isEqualTo(9000000000000050.0);
    }

    @Test
    void snapshotWritesScoresAndRemovesZeroBalanceUsers() {
        leaderboard.apply(event(0, 9000000000000020L, 1));
        leaderboard.apply(event(1, 9000000000000040L, 1));
        leaderboard.restore(
                List.of(new PointsAccount(prefix + 0, 9000000000000050L, 2), new PointsAccount(prefix + 1, 0, 2)));
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, prefix + 0))
                .isEqualTo(9000000000000050.0);
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, prefix + 1))
                .isNull();
        assertThat(redis.opsForHash().get(RedisLeaderboardRepository.VERSIONS, prefix + 0))
                .isEqualTo(String.format(Locale.ROOT, "%019d", 2L));
        assertThat(redis.opsForHash().get(RedisLeaderboardRepository.VERSIONS, prefix + 1))
                .isEqualTo(String.format(Locale.ROOT, "%019d", 2L));
    }

    @Test
    void snapshotPreservesLowerRanksAndZeroBalanceRevisionMarkers() {
        final List<PointsAccount> snapshot = new ArrayList<>();
        snapshot.add(new PointsAccount(prefix + 0, 0, 2));
        for (int i = 1; i < 12; i++) {
            snapshot.add(new PointsAccount(prefix + i, 9000000000000000L + i, 1));
        }
        leaderboard.restore(snapshot);
        assertThat(leaderboard.topTen()).extracting(PointsAccount::userId).doesNotContain(prefix + 1);
        leaderboard.remove(new PointsAccount(prefix + 11, 0, 2));
        assertThat(leaderboard.topTen()).extracting(PointsAccount::userId).contains(prefix + 1);
        leaderboard.apply(event(0, 9000000000000100L, 1));
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, prefix + 0))
                .isNull();
    }
}
