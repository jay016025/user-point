package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.cache.transaction.TransactionAwareCacheManagerProxy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.demo.config.PointsRetryConfiguration;
import com.example.demo.dao.points.PointsAccountJpaRepository;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.messaging.MessagePublisher;
import com.example.demo.messaging.OutboxWorker;
import com.example.demo.model.entity.points.PointsAccountEntity;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;
import com.example.demo.persistence.mapper.PointsPersistenceMapperImpl;
import com.example.demo.persistence.outbox.JpaOutboxRepository;
import com.example.demo.persistence.outbox.OutboxRepository;
import com.example.demo.persistence.points.JpaPointsRepository;
import com.example.demo.service.PointsDeletionService;
import com.example.demo.service.PointsRules;
import com.example.demo.service.PointsService;
import com.example.demo.service.PointsTotalReader;
import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.impl.PointsServiceImpl;
import com.example.demo.service.mapper.PointsServiceMapperImpl;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.AddPointsResult;
import com.example.demo.service.result.LeaderboardEntryResult;

@DataJpaTest(
        showSql = false,
        properties = {
            "spring.datasource.url=jdbc:h2:mem:points;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
    PointsServiceImpl.class,
    PointsDeletionService.class,
    PointsRetryConfiguration.class,
    JpaPointsRepository.class,
    PointsPersistenceTest.Caches.class,
    JpaOutboxRepository.class,
    OutboxWorker.class,
    PointsTotalReader.class,
    PointsServiceMapperImpl.class,
    PointsPersistenceMapperImpl.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PointsPersistenceTest {
    @TestConfiguration
    @EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
    static class Caches {
        @Bean
        CacheManager cacheManager() {
            return new TransactionAwareCacheManagerProxy(new ConcurrentMapCacheManager("points"));
        }
    }

    @Autowired
    private PointsService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CacheManager caches;

    @MockitoSpyBean
    private JpaPointsRepository repository;

    @MockitoSpyBean
    private PointsAccountJpaRepository accounts;

    @MockitoBean
    private LeaderboardRepository leaderboard;

    @MockitoBean
    private MessagePublisher publisher;

    @Autowired
    private OutboxWorker worker;

    @Autowired
    private OutboxRepository outbox;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM points_outbox");
        jdbc.update("DELETE FROM point_record");
        jdbc.update("DELETE FROM points_account");
        caches.getCache("points").clear();
    }

    @Test
    void persistsRecordTotalAndOutboxTogether() {
        final AddPointsResult record = service.add(new AddPointsCommand("user_1", 100, "bonus"));
        assertThat(record.id()).isPositive();
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        assertThat(service.updateReason(new UpdateReasonCommand(record.id(), "updated"))
                        .reason())
                .isEqualTo("updated");
        assertThat(jdbc.queryForObject("SELECT reason FROM point_record WHERE id=?", String.class, record.id()))
                .isEqualTo("updated");
    }

    @Test
    void deletingMissingUserDoesNotCreateAccountOrOutbox() {
        service.delete(new DeletePointsCommand("unknown"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_account", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isZero();
    }

    @Test
    void deletingExistingUserDoesNotEnqueueNotification() {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        service.delete(new DeletePointsCommand("user_1"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT total FROM points_account", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE deleted_at IS NULL", Long.class))
                .isZero();
    }

    @Test
    void emptyRedisLeaderboardFallsBackToDatabaseTopTen() {
        for (int i = 1; i <= 12; i++) {
            jdbc.update(
                    "INSERT INTO points_account (user_id, total, version) VALUES (?, ?, 1)",
                    String.format(Locale.ROOT, "user_%03d", i),
                    Math.min(i, 10) * 100L);
        }
        jdbc.update("INSERT INTO points_account (user_id, total, version) VALUES (?, 0, 2)", "deleted");
        assertThat(service.leaderboard().entries())
                .extracting(LeaderboardEntryResult::userId)
                .containsExactly(
                        "user_012",
                        "user_011",
                        "user_010",
                        "user_009",
                        "user_008",
                        "user_007",
                        "user_006",
                        "user_005",
                        "user_004",
                        "user_003");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isZero();
    }

    @Test
    void accountVersionIsManagedByJpa() {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            final PointsAccountEntity account = entityManager.find(PointsAccountEntity.class, "user_1");
            account.setTotal(150);
            entityManager.flush();
            assertThat(account.getVersion()).isEqualTo(2);
        });
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(2);
    }

    @Test
    void zeroBalanceDeletionsAdvanceRevisionWithoutCreatingEvents() {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        service.delete(new DeletePointsCommand("user_1"));
        service.delete(new DeletePointsCommand("user_1"));
        service.add(new AddPointsCommand("user_1", 10, "new"));
        assertThat(jdbc.queryForList("SELECT version FROM points_outbox ORDER BY version", Long.class))
                .containsExactly(1L, 4L);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(4);
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(10);
    }

    @Test
    void outboxFailureRollsBackRecordAndAccount() {
        doThrow(new DataAccessResourceFailureException("outbox failed"))
                .when(repositoryTarget())
                .enqueue(any());
        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 100, "bonus")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_account", Long.class))
                .isZero();
    }

    @Test
    void cacheHitsAvoidDatabaseAndWritesEvictAfterCommit() {
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
        verify(repository, times(1)).findTotal("user_1");
        service.add(new AddPointsCommand("user_1", 100, "bonus"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        service.delete(new DeletePointsCommand("user_1"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE deleted_at IS NULL", Long.class))
                .isZero();
    }

    @Test
    void deletionRetainsHistoryAndPreventsFurtherReasonUpdates() {
        final AddPointsResult old = service.add(new AddPointsCommand("user_1", 100, "original"));
        final AddPointsResult other = service.add(new AddPointsCommand("user_2", 50, "other"));
        service.delete(new DeletePointsCommand("user_1"));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE id=?", Long.class, old.id()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM point_record WHERE id=?", Timestamp.class, old.id()))
                .isNotNull();
        assertThat(repository.findRecord(old.id())).isEmpty();
        assertThatThrownBy(() -> service.updateReason(new UpdateReasonCommand(old.id(), "changed")))
                .isInstanceOfSatisfying(BusinessException.class, exception -> assertThat(exception.errorCode())
                        .isEqualTo(ErrorCode.RECORD_NOT_FOUND));
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        final Boolean updated = transaction.execute(status -> repository.updateReason(old.id(), "changed"));
        assertThat(updated).isFalse();
        assertThat(jdbc.queryForObject("SELECT reason FROM point_record WHERE id=?", String.class, old.id()))
                .isEqualTo("original");
        assertThat(repository.findRecord(other.id())).isPresent();
        assertThat(service.total(new GetPointsQuery("user_2")).total()).isEqualTo(50);
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
    }

    @Test
    void repeatedDeletionPreservesHistoryAndNewPointsStartFromZero() {
        final AddPointsResult old = service.add(new AddPointsCommand("user_1", 100, "old"));
        service.delete(new DeletePointsCommand("user_1"));
        final Timestamp deletedAt =
                jdbc.queryForObject("SELECT deleted_at FROM point_record WHERE id=?", Timestamp.class, old.id());
        assertThat(deletedAt).isNotNull();
        service.delete(new DeletePointsCommand("user_1"));
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM point_record WHERE id=?", Timestamp.class, old.id()))
                .isEqualTo(deletedAt);

        final AddPointsResult added = service.add(new AddPointsCommand("user_1", 20, "new"));
        assertThat(added.id()).isNotEqualTo(old.id());
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(20);
        assertThat(repository.findRecord(old.id())).isEmpty();
        assertThat(repository.findRecord(added.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE deleted_at IS NULL", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(4);
    }

    @Test
    void failedDeletionRollsBackHistoryTotalVersionWithoutRedisCleanup() {
        final AddPointsResult added = service.add(new AddPointsCommand("user_1", 100, "original"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        doThrow(new DataAccessResourceFailureException("delete failed"))
                .when(repositoryTarget())
                .deleteRecords("user_1");

        assertThatThrownBy(() -> service.delete(new DeletePointsCommand("user_1")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(repository.findRecord(added.id())).isPresent();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM point_record WHERE id=?", Timestamp.class, added.id()))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT total FROM points_account", Long.class))
                .isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        verify(leaderboard, times(0)).remove(any());
    }

    @Test
    void failedWriteDoesNotEvictPreviouslyCommittedTotal() {
        service.add(new AddPointsCommand("user_1", 100, "bonus"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        doThrow(new DataAccessResourceFailureException("fail"))
                .when(repositoryTarget())
                .enqueue(any());
        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 10, "bonus")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT total FROM points_account WHERE user_id=?", Long.class, "user_1"))
                .isEqualTo(100);
    }

    @Test
    void optimisticConflictRetriesWholeTransactionWithoutDuplicatingPoints() {
        doThrow(new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, "user_1"))
                .doCallRealMethod()
                .when(repositoryTarget())
                .enqueue(any());

        service.add(new AddPointsCommand("user_1", 100, "bonus"));

        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(1);
        verify(repositoryTarget(), times(2)).enqueue(any());
    }

    @Test
    void optimisticDeletionConflictRetriesHistoryAndAccountTogether() {
        final AddPointsResult added = service.add(new AddPointsCommand("user_1", 100, "seed"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        doThrow(new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, "user_1"))
                .doCallRealMethod()
                .when(repositoryTarget())
                .deleteRecords("user_1");

        service.delete(new DeletePointsCommand("user_1"));

        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM point_record WHERE id=?", Timestamp.class, added.id()))
                .isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(2);
        assertThat(caches.getCache("points").get("user_1", Long.class)).isZero();
        verify(repositoryTarget(), times(2)).deleteRecords("user_1");
        verify(leaderboard, times(1)).remove(new PointsAccount("user_1", 0, 2));
    }

    @Test
    void accountCreationIntegrityFailuresRetryThreeTimesWithoutWritingData() {
        final DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed");
        doThrow(failure).when(accounts).saveAndFlush(any(PointsAccountEntity.class));
        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 100, "bonus")))
                .isInstanceOf(OptimisticLockingFailureException.class)
                .hasCause(failure);
        verify(accounts, times(3)).saveAndFlush(any(PointsAccountEntity.class));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_account", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isZero();
    }

    @Test
    void persistentOptimisticConflictsStopAfterThreeAttemptsAndRollBackEverything() {
        caches.getCache("points").put("user_1", 0L);
        doThrow(new ObjectOptimisticLockingFailureException(PointsAccountEntity.class, "user_1"))
                .when(repositoryTarget())
                .enqueue(any());

        assertThatThrownBy(() -> service.add(new AddPointsCommand("user_1", 100, "bonus")))
                .isInstanceOf(OptimisticLockingFailureException.class);

        verify(repositoryTarget(), times(3)).enqueue(any());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_account", Long.class))
                .isZero();
        assertThat(caches.getCache("points").get("user_1", Long.class)).isZero();
    }

    @Test
    void concurrentAccountReadsRetryConflictingAddsWithoutLosingPoints() throws Exception {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        synchronizeFirstTwoAccountReads();
        try (final ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> first = pool.submit(() -> service.add(new AddPointsCommand("user_1", 10, "first")));
            final Future<?> second = pool.submit(() -> service.add(new AddPointsCommand("user_1", 20, "second")));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(130);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentAddAndDeleteKeepActiveRecordsAndTotalConsistent() throws Exception {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        synchronizeFirstTwoAccountReads();
        try (final ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> add = pool.submit(() -> service.add(new AddPointsCommand("user_1", 10, "parallel")));
            final Future<?> delete = pool.submit(() -> service.delete(new DeletePointsCommand("user_1")));
            add.get(10, TimeUnit.SECONDS);
            delete.get(10, TimeUnit.SECONDS);
        }
        final long total = service.total(new GetPointsQuery("user_1")).total();
        assertThat(total).isIn(0L, 10L);
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount),0) FROM point_record WHERE deleted_at IS NULL", Long.class))
                .isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(3);
    }

    @Test
    void concurrentAddAndZeroBalanceDeleteKeepRevisionAndRecordsConsistent() throws Exception {
        service.add(new AddPointsCommand("user_1", 100, "seed"));
        service.delete(new DeletePointsCommand("user_1"));
        synchronizeFirstTwoAccountReads();
        try (final ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> add = pool.submit(() -> service.add(new AddPointsCommand("user_1", 10, "parallel")));
            final Future<?> delete = pool.submit(() -> service.delete(new DeletePointsCommand("user_1")));
            add.get(10, TimeUnit.SECONDS);
            delete.get(10, TimeUnit.SECONDS);
        }
        final long total = service.total(new GetPointsQuery("user_1")).total();
        assertThat(total).isIn(0L, 10L);
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount),0) FROM point_record WHERE deleted_at IS NULL", Long.class))
                .isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT MAX(version) FROM points_outbox", Long.class))
                .isIn(3L, 4L);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(4);
    }

    @Test
    void redisDeletionFailureCanBeRetriedWithoutCreatingNotifications() {
        final AddPointsResult record = service.add(new AddPointsCommand("user_1", 100, "seed"));
        doThrow(new SystemException(ErrorCode.CACHE_ERROR, new IllegalStateException("redis down")))
                .doNothing()
                .when(leaderboard)
                .remove(any());
        assertThatThrownBy(() -> service.delete(new DeletePointsCommand("user_1")))
                .isInstanceOf(SystemException.class);
        assertThat(jdbc.queryForObject("SELECT total FROM points_account", Long.class))
                .isZero();
        assertThat(repository.findRecord(record.id())).isEmpty();
        service.delete(new DeletePointsCommand("user_1"));
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(1);
        verify(leaderboard, times(2)).remove(any());
    }

    private void synchronizeFirstTwoAccountReads() {
        final CountDownLatch ready = new CountDownLatch(2);
        final AtomicInteger reads = new AtomicInteger();
        doAnswer(invocation -> {
                    final Object account = invocation.callRealMethod();
                    if (reads.incrementAndGet() <= 2) {
                        ready.countDown();
                        if (!ready.await(3, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Concurrent account reads were blocked");
                        }
                    }
                    return account;
                })
                .when(repositoryTarget())
                .findAccount("user_1");
    }

    @Test
    void concurrentExistingAccountWritesCommitOnlySuccessfulAttempts() throws Exception {
        // First-account duplicate-key retries are verified against MySQL in ExternalServicesIT.
        jdbc.update("INSERT INTO points_account (user_id, total, version) VALUES (?, 0, 0)", "parallel");
        int committed = 0;
        try (final ExecutorService pool = Executors.newFixedThreadPool(6)) {
            final List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                results.add(pool.submit(() -> service.add(new AddPointsCommand("parallel", 5, "bonus"))));
            }
            for (final Future<?> result : results) {
                try {
                    result.get(20, TimeUnit.SECONDS);
                    committed++;
                } catch (final ExecutionException exception) {
                    // Conflicts may exhaust the bounded retry budget; other failures are unexpected.
                    assertThat(exception.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }
        }
        assertThat(committed).isBetween(1, 24);
        assertThat(service.total(new GetPointsQuery("parallel")).total()).isEqualTo(committed * 5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record", Long.class))
                .isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox", Long.class))
                .isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account", Long.class))
                .isEqualTo(committed);
    }

    @Test
    void userIdsRemainCaseSensitive() {
        service.add(new AddPointsCommand("User", 10, "bonus"));
        service.add(new AddPointsCommand("user", 20, "bonus"));
        assertThat(service.total(new GetPointsQuery("User")).total()).isEqualTo(10);
        assertThat(service.total(new GetPointsQuery("user")).total()).isEqualTo(20);
    }

    @Test
    void failedDispatchRollsBackDeliveryFlagAndCanBeRetried() {
        service.add(new AddPointsCommand("user_1", 100, "bonus"));
        final String eventId = outbox.pendingIds(10).getFirst();
        doThrow(new SystemException(ErrorCode.MESSAGE_ERROR, new IllegalStateException("broker down")))
                .when(publisher)
                .publish(any());
        assertThatThrownBy(() -> worker.process(eventId)).isInstanceOf(SystemException.class);
        assertThat(outbox.pendingIds(10)).contains(eventId);
        assertThat(service.total(new GetPointsQuery("user_1")).total()).isEqualTo(100);
        reset(publisher);
        assertThat(worker.process(eventId)).isTrue();
        assertThat(outbox.pendingIds(10)).doesNotContain(eventId);
        assertThat(worker.process(eventId)).isFalse();
        verify(publisher, times(1)).publish(any());
    }

    private JpaPointsRepository repositoryTarget() {
        return AopTestUtils.getUltimateTargetObject(repository);
    }

    @Test
    void exactMaximumAndEventContractSurviveJpaRoundTrip() {
        final AddPointsResult added = service.add(new AddPointsCommand("Case_1", PointsRules.MAX_TOTAL, "活動獎勵"));
        assertThat(service.total(new GetPointsQuery("Case_1")).total()).isEqualTo(PointsRules.MAX_TOTAL);
        final String eventId = outbox.pendingIds(10).getFirst();
        final TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        final PointsChanged event =
                transaction.execute(status -> outbox.lockPending(eventId).orElseThrow());
        assertThat(event).isNotNull();
        assertThat(event.userId()).isEqualTo("Case_1");
        assertThat(event.type()).isEqualTo(PointsEventType.POINTS_ADDED);
        assertThat(event.recordId()).isEqualTo(added.id());
        assertThat(event.amount()).isEqualTo(PointsRules.MAX_TOTAL);
        assertThat(event.total()).isEqualTo(PointsRules.MAX_TOTAL);
        assertThat(event.version()).isEqualTo(1);
        assertThat(event.reason()).isEqualTo("活動獎勵");
    }
}
