package com.example.demo.service;

import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.domain.PointsAccount;
import com.example.demo.persistence.points.PointsRepository;

/** Commit database deletion before the caller cleans up Redis. */
@Service
public class PointsDeletionService {
    private final PointsRepository repository;

    public PointsDeletionService(final PointsRepository repository) {
        this.repository = repository;
    }

    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5, multiplier = 2, maxDelay = 100, random = true))
    @Transactional(rollbackFor = Exception.class)
    public Optional<PointsAccount> delete(final String userId) {
        final Optional<PointsAccount> existing = repository.findAccount(userId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        final PointsAccount account = existing.orElseThrow();
        final PointsAccount saved = repository.saveAccount(new PointsAccount(userId, 0, account.version()));
        repository.deleteRecords(userId);
        return Optional.of(saved);
    }
}
