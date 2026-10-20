package com.example.demo.persistence.points;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dao.outbox.PointsOutboxJpaRepository;
import com.example.demo.dao.points.PointRecordJpaRepository;
import com.example.demo.dao.points.PointsAccountJpaRepository;
import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.model.entity.points.PointsAccountEntity;
import com.example.demo.persistence.mapper.PointsPersistenceMapper;

@Repository
public class JpaPointsRepository implements PointsRepository {
    private final PointsAccountJpaRepository accounts;
    private final PointRecordJpaRepository records;
    private final PointsOutboxJpaRepository outbox;
    private final PointsPersistenceMapper mapper;

    public JpaPointsRepository(
            final PointsAccountJpaRepository accounts,
            final PointRecordJpaRepository records,
            final PointsOutboxJpaRepository outbox,
            final PointsPersistenceMapper mapper) {
        this.accounts = accounts;
        this.records = records;
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PointsAccount> findAccount(final String userId) {
        return accounts.findById(userId).map(mapper::account);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PointsAccount findOrCreateAccount(final String userId) {
        return findAccount(userId).orElseGet(() -> mapper.account(createAccount(userId)));
    }

    private PointsAccountEntity createAccount(final String userId) {
        try {
            return accounts.saveAndFlush(mapper.newAccount(userId));
        } catch (final DataIntegrityViolationException exception) {
            // Retry account-creation integrity failures in a new use-case transaction.
            throw new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, userId, exception);
        }
    }

    @Override
    public long findTotal(final String userId) {
        return accounts.findTotal(userId).orElse(0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PointsAccount> findLeaderboardSnapshot() {
        return accounts.findLeaderboardSnapshot().stream().map(mapper::account).toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PointRecord insert(final PointRecord record) {
        return mapper.record(records.saveAndFlush(mapper.newRecord(record)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public PointsAccount saveAccount(final PointsAccount account) {
        final PointsAccountEntity entity = accounts.findById(account.userId()).orElseThrow();
        if (entity.getVersion() != account.version()) {
            throw new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, account.userId());
        }
        if (entity.getTotal() == account.total()) {
            // Hibernate does not increment @Version for an unchanged entity. Use a conditional
            // version update so zero-balance deletions still invalidate older additions.
            if (accounts.advanceVersion(account.userId(), account.version()) != 1) {
                throw new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, account.userId());
            }
            return mapper.account(accounts.findById(account.userId()).orElseThrow());
        }
        mapper.updateAccount(account, entity);
        // Flush before changing detail records and constructing the event with the new @Version.
        accounts.flush();
        return mapper.account(entity);
    }

    @Override
    public Optional<PointRecord> findRecord(final long id) {
        return records.findByIdAndDeletedAtIsNull(id).map(mapper::record);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean updateReason(final long id, final String reason) {
        return records.updateReason(id, reason) > 0;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void deleteRecords(final String userId) {
        records.softDeleteRecords(userId, Instant.now());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(final PointsChanged event) {
        outbox.saveAndFlush(mapper.outbox(event));
    }
}
