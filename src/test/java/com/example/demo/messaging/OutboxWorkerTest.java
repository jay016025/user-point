package com.example.demo.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;

import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;
import com.example.demo.persistence.outbox.OutboxRepository;

class OutboxWorkerTest {
    private final OutboxRepository repository = mock(OutboxRepository.class);
    private final LeaderboardRepository leaderboard = mock(LeaderboardRepository.class);
    private final MessagePublisher publisher = mock(MessagePublisher.class);
    private final ConcurrentMapCacheManager caches = new ConcurrentMapCacheManager("points");
    private final OutboxWorker worker = new OutboxWorker(repository, leaderboard, publisher, caches);
    private final PointsChanged event = new PointsChanged(
            "event-id",
            PointsEventType.POINTS_ADDED,
            "user_1",
            7,
            100,
            120,
            4,
            "bonus",
            Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void publishesAndEvictsCacheBeforeMarkingDelivered() {
        when(repository.lockPending("event-id")).thenReturn(Optional.of(event));
        caches.getCache("points").put("user_1", 20L);
        assertThat(worker.process("event-id")).isTrue();
        verify(leaderboard).apply(event);
        verify(publisher).publish(event);
        assertThat(caches.getCache("points").get("user_1")).isNull();
        verify(repository).markDelivered("event-id");
    }

    @Test
    void deletionUpdatesRedisWithoutPublishingToRocketMq() {
        final PointsChanged deletion = deletion();
        when(repository.lockPending(deletion.eventId())).thenReturn(Optional.of(deletion));
        caches.getCache("points").put("user_1", 120L);

        assertThat(worker.process(deletion.eventId())).isTrue();

        verify(leaderboard).apply(deletion);
        assertThat(caches.getCache("points").get("user_1")).isNull();
        verify(repository).markDelivered(deletion.eventId());
        verifyNoInteractions(publisher);
    }

    @Test
    void failedDeletionCacheEvictionCanRetryWithoutCallingRocketMq() {
        final PointsChanged deletion = deletion();
        when(repository.lockPending(deletion.eventId())).thenReturn(Optional.of(deletion));
        final CacheManager failingManager = mock(CacheManager.class);
        final Cache failingCache = mock(Cache.class);
        when(failingManager.getCache("points")).thenReturn(failingCache);
        when(failingCache.evictIfPresent("user_1"))
                .thenThrow(new RedisConnectionFailureException("down"))
                .thenReturn(true);
        final OutboxWorker retryWorker = new OutboxWorker(repository, leaderboard, publisher, failingManager);

        assertThatThrownBy(() -> retryWorker.process(deletion.eventId()))
                .isInstanceOfSatisfying(
                        SystemException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CACHE_ERROR));
        verify(repository, never()).markDelivered(any());
        assertThat(retryWorker.process(deletion.eventId())).isTrue();
        verify(repository).markDelivered(deletion.eventId());
        verifyNoInteractions(publisher);
    }

    private PointsChanged deletion() {
        return new PointsChanged(
                "delete-id", PointsEventType.POINTS_DELETED, "user_1", 0, 0, 0, 5, "", event.occurredAt());
    }

    @Test
    void brokerFailureLeavesEventPendingForRetry() {
        when(repository.lockPending("event-id")).thenReturn(Optional.of(event));
        doThrow(new SystemException(ErrorCode.MESSAGE_ERROR, new IllegalStateException("broker down")))
                .when(publisher)
                .publish(event);
        assertThatThrownBy(() -> worker.process("event-id")).isInstanceOf(SystemException.class);
        verify(repository, never()).markDelivered(any());
    }

    @Test
    void deliveredOrMissingEventIsSkipped() {
        when(repository.lockPending("event-id")).thenReturn(Optional.empty());
        assertThat(worker.process("event-id")).isFalse();
        verifyNoInteractions(leaderboard, publisher);
    }

    @Test
    void cacheFailureMustKeepEventPendingBeforeAcknowledgement() {
        when(repository.lockPending("event-id")).thenReturn(Optional.of(event));
        final CacheManager failingManager = mock(CacheManager.class);
        final Cache failingCache = mock(Cache.class);
        when(failingManager.getCache("points")).thenReturn(failingCache);
        when(failingCache.evictIfPresent("user_1")).thenThrow(new RedisConnectionFailureException("down"));
        final OutboxWorker failingWorker = new OutboxWorker(repository, leaderboard, publisher, failingManager);
        assertThatThrownBy(() -> failingWorker.process("event-id"))
                .isInstanceOfSatisfying(
                        SystemException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CACHE_ERROR));
        verify(repository, never()).markDelivered(any());
        verifyNoInteractions(publisher);
    }
}
