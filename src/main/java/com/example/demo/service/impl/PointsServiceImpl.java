package com.example.demo.service.impl;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.domain.PointsLeaderboard;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;
import com.example.demo.persistence.points.PointsRepository;
import com.example.demo.service.PointsDeletionService;
import com.example.demo.service.PointsRules;
import com.example.demo.service.PointsService;
import com.example.demo.service.PointsTotalReader;
import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.mapper.PointsServiceMapper;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.AddPointsResult;
import com.example.demo.service.result.DeletePointsResult;
import com.example.demo.service.result.GetLeaderboardResult;
import com.example.demo.service.result.GetPointsResult;
import com.example.demo.service.result.UpdateReasonResult;

@Service
public class PointsServiceImpl implements PointsService {
    private static final String CACHE = PointsTotalReader.CACHE;
    private final PointsRepository repository;
    private final LeaderboardRepository leaderboard;
    private final ApplicationEventPublisher events;
    private final PointsServiceMapper mapper;
    private final PointsTotalReader totals;
    private final PointsDeletionService deletions;

    public PointsServiceImpl(
            final PointsRepository repository,
            final LeaderboardRepository leaderboard,
            final ApplicationEventPublisher events,
            final PointsServiceMapper mapper,
            final PointsTotalReader totals,
            final PointsDeletionService deletions) {
        this.repository = repository;
        this.leaderboard = leaderboard;
        this.events = events;
        this.mapper = mapper;
        this.totals = totals;
        this.deletions = deletions;
    }

    @Override
    @Retryable(
            retryFor = OptimisticLockingFailureException.class,
            maxAttempts = 3,
            backoff = @Backoff(delay = 5, multiplier = 2, maxDelay = 100, random = true))
    @Transactional(rollbackFor = Exception.class)
    @CacheEvict(cacheNames = CACHE, key = "#command.userId()")
    public AddPointsResult add(final AddPointsCommand command) {
        PointsRules.userId(command.userId());
        PointsRules.reason(command.reason());
        if (command.amount() <= 0 || command.amount() > PointsRules.MAX_TOTAL) {
            PointsRules.invalid();
        }
        final PointsAccount account = repository.findOrCreateAccount(command.userId());
        if (command.amount() > PointsRules.MAX_TOTAL - account.total()) {
            throw new BusinessException(ErrorCode.POINTS_LIMIT_EXCEEDED);
        }
        final long total = account.total() + command.amount();
        final PointsAccount saved =
                repository.saveAccount(new PointsAccount(account.userId(), total, account.version()));
        final PointRecord record = repository.insert(mapper.newRecord(command));
        recordEvent(new PointsChanged(
                UUID.randomUUID().toString(),
                PointsEventType.POINTS_ADDED,
                account.userId(),
                record.id(),
                record.amount(),
                total,
                saved.version(),
                record.reason(),
                Instant.now()));
        return mapper.added(record);
    }

    @Override
    public GetPointsResult total(final GetPointsQuery query) {
        PointsRules.userId(query.userId());
        return mapper.total(query, totals.total(query.userId()));
    }

    @Override
    public GetLeaderboardResult leaderboard() {
        final List<PointsAccount> cached = leaderboard.topTen();
        if (!cached.isEmpty()) {
            return mapper.leaderboard(new PointsLeaderboard(cached));
        }
        final List<PointsAccount> snapshot = repository.findLeaderboardSnapshot();
        if (!snapshot.isEmpty()) {
            leaderboard.restore(snapshot);
        }
        final List<PointsAccount> topTen = snapshot.stream()
                .filter(account -> account.total() > 0)
                .limit(10)
                .toList();
        return mapper.leaderboard(new PointsLeaderboard(topTen));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UpdateReasonResult updateReason(final UpdateReasonCommand command) {
        if (command.id() <= 0) {
            PointsRules.invalid();
        }
        PointsRules.reason(command.reason());
        final PointRecord record = repository
                .findRecord(command.id())
                .orElseThrow(() -> new BusinessException(ErrorCode.RECORD_NOT_FOUND));
        if (!repository.updateReason(command.id(), command.reason())) {
            throw new BusinessException(ErrorCode.RECORD_NOT_FOUND);
        }
        // The total-points cache and leaderboard contain no reason; neither needs eviction.
        return mapper.updated(mapper.changedReason(record, command));
    }

    @Override
    @CacheEvict(cacheNames = CACHE, key = "#command.userId()")
    public DeletePointsResult delete(final DeletePointsCommand command) {
        PointsRules.userId(command.userId());
        // The database transaction has committed before Redis cleanup starts.
        deletions.delete(command.userId()).ifPresent(leaderboard::remove);
        return mapper.deleted(command);
    }

    private void recordEvent(final PointsChanged event) {
        repository.enqueue(event);
        events.publishEvent(event);
    }
}
