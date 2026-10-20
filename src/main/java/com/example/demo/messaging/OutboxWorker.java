package com.example.demo.messaging;

import java.util.Objects;
import java.util.Optional;

import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;
import com.example.demo.persistence.outbox.OutboxRepository;
import com.example.demo.service.PointsTotalReader;

@Service
public class OutboxWorker {
    private final OutboxRepository repository;
    private final LeaderboardRepository leaderboard;
    private final MessagePublisher publisher;
    private final CacheManager caches;

    public OutboxWorker(
            final OutboxRepository repository,
            final LeaderboardRepository leaderboard,
            final MessagePublisher publisher,
            final CacheManager caches) {
        this.repository = repository;
        this.leaderboard = leaderboard;
        this.publisher = publisher;
        this.caches = caches;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean process(final String eventId) {
        // Lock the outbox row across dispatch so multiple application instances do not send it simultaneously.
        final Optional<PointsChanged> pending = repository.lockPending(eventId);
        if (pending.isEmpty()) {
            return false;
        }
        final PointsChanged event = pending.orElseThrow();
        leaderboard.apply(event);
        try {
            // evictIfPresent is immediate even with a transaction-aware CacheManager.
            // A deferred eviction could fail after delivered=true was already committed.
            Objects.requireNonNull(caches.getCache(PointsTotalReader.CACHE)).evictIfPresent(event.userId());
        } catch (final RuntimeException exception) {
            throw new SystemException(ErrorCode.CACHE_ERROR, exception);
        }
        // New outbox events are additions. Historical deletion rows only finish Redis cleanup.
        if (event.type() == PointsEventType.POINTS_ADDED) {
            publisher.publish(event);
            // A crash after broker acknowledgement can cause redelivery. Consumers must deduplicate by eventId.
        }
        repository.markDelivered(eventId);
        return true;
    }
}
