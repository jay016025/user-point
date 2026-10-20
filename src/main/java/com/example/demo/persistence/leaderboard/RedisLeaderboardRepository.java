package com.example.demo.persistence.leaderboard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.DefaultTypedTuple;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.model.redis.LeaderboardUpdate;
import com.example.demo.persistence.mapper.LeaderboardRedisMapper;

@Component
public class RedisLeaderboardRepository implements LeaderboardRepository {
    // 共用 {leaderboard} hash tag，讓 Redis Cluster 將這兩個 key 分配到同一個 slot。
    public static final String SCORES = "points:{leaderboard}:scores";
    public static final String VERSIONS = "points:{leaderboard}:versions";

    // 單筆加分／刪除：在 Redis 內原子完成版本檢查與更新。
    private static final DefaultRedisScript<Long> APPLY = new DefaultRedisScript<>(
            """
            -- KEYS：排行榜 Sorted Set、使用者版本 Hash。
            -- ARGV：userId、補零為 19 位的版本、積分總額。
            local scoresKey = KEYS[1]
            local versionsKey = KEYS[2]
            local userId = ARGV[1]
            local incomingVersion = ARGV[2]
            local total = ARGV[3]

            -- 等長數字字串可直接比較，避免轉成 Lua 浮點數時遺失 BIGINT 精度。
            local storedVersion = redis.call('HGET', versionsKey, userId)
            if storedVersion and storedVersion >= incomingVersion then
                return 0
            end

            -- 保留零分版本，阻擋舊加分事件讓使用者重新上榜。
            redis.call('HSET', versionsKey, userId, incomingVersion)
            if total == '0' then
                redis.call('ZREM', scoresKey, userId)
            else
                redis.call('ZADD', scoresKey, total, userId)
            end
            return 1
            """,
            Long.class);
    private final StringRedisTemplate redis;
    private final LeaderboardRedisMapper mapper;

    public RedisLeaderboardRepository(final StringRedisTemplate redis, final LeaderboardRedisMapper mapper) {
        this.redis = redis;
        this.mapper = mapper;
    }

    @Override
    public List<PointsAccount> topTen() {
        try {
            final Set<ZSetOperations.TypedTuple<String>> tuples = Optional.ofNullable(
                            redis.opsForZSet().reverseRangeWithScores(SCORES, 0, 9))
                    .orElseGet(Collections::emptySet);
            return tuples.stream().map(mapper::account).toList();
        } catch (final DataAccessException exception) {
            throw new SystemException(ErrorCode.CACHE_ERROR, exception);
        }
    }

    @Override
    public void apply(final PointsChanged event) {
        applyUpdate(mapper.update(event));
    }

    @Override
    public void remove(final PointsAccount account) {
        applyUpdate(mapper.removal(account));
    }

    @Override
    public void restore(final List<PointsAccount> accounts) {
        final Map<String, String> versions = new HashMap<>();
        final Set<ZSetOperations.TypedTuple<String>> scores = new HashSet<>();
        final List<String> zeroBalanceUsers = new ArrayList<>();
        for (final PointsAccount account : accounts) {
            final LeaderboardUpdate update = mapper.snapshot(account);
            versions.put(update.userId(), versionArgument(update.version()));
            if (update.total() > 0) {
                scores.add(new DefaultTypedTuple<>(update.userId(), (double) update.total()));
            } else {
                zeroBalanceUsers.add(update.userId());
            }
        }
        try {
            // 回填直接批次寫入，不做原子版本比較。
            redis.opsForHash().putAll(VERSIONS, versions);
            if (!zeroBalanceUsers.isEmpty()) {
                redis.opsForZSet().remove(SCORES, zeroBalanceUsers.toArray());
            }
            if (!scores.isEmpty()) {
                redis.opsForZSet().add(SCORES, scores);
            }
        } catch (final DataAccessException exception) {
            throw new SystemException(ErrorCode.CACHE_ERROR, exception);
        }
    }

    private void applyUpdate(final LeaderboardUpdate update) {
        try {
            redis.execute(
                    APPLY,
                    List.of(SCORES, VERSIONS),
                    update.userId(),
                    versionArgument(update.version()),
                    Long.toString(update.total()));
        } catch (final DataAccessException exception) {
            throw new SystemException(ErrorCode.CACHE_ERROR, exception);
        }
    }

    private String versionArgument(final long version) {
        return String.format(Locale.ROOT, "%019d", version);
    }
}
