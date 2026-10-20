package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.rocketmq.client.consumer.DefaultMQPullConsumer;
import org.apache.rocketmq.client.consumer.PullResult;
import org.apache.rocketmq.common.message.MessageExt;
import org.apache.rocketmq.common.message.MessageQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.messaging.MessagePublisher;
import com.example.demo.messaging.OutboxWorker;
import com.example.demo.persistence.leaderboard.RedisLeaderboardRepository;
import com.example.demo.persistence.outbox.OutboxRepository;
import com.example.demo.persistence.points.JpaPointsRepository;
import com.example.demo.service.PointsService;
import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.query.GetPointsQuery;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "points.outbox.delay-ms=60000")
@AutoConfigureMockMvc
class ExternalServicesIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private PointsService service;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxWorker worker;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private MessagePublisher publisher;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private JpaPointsRepository repository;

    private final String user = "it_" + UUID.randomUUID().toString().replace("-", "");

    @AfterEach
    void clean() {
        service.delete(new DeletePointsCommand(user));
        // Delete only this test's data; never flush shared Redis or truncate the database.
        jdbc.update("DELETE FROM points_outbox WHERE user_id=?", user);
        jdbc.update("DELETE FROM point_record WHERE user_id=?", user);
        jdbc.update("DELETE FROM points_account WHERE user_id=?", user);
        redis.opsForHash().delete(RedisLeaderboardRepository.VERSIONS, user);
    }

    @Test
    void deletingUnknownUserDoesNotCreateMysqlOrRedisEntries() throws Exception {
        mvc.perform(delete("/points/" + user)).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_account WHERE user_id=?", Long.class, user))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox WHERE user_id=?", Long.class, user))
                .isZero();
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isNull();
        assertThat(redis.opsForHash().get(RedisLeaderboardRepository.VERSIONS, user))
                .isNull();
        verify(publisher, times(0)).publish(any());
    }

    @Test
    void fullHttpLifecycleUsesMysqlRedisAndRocketMq() throws Exception {
        final String response = mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("userId", user, "amount", 100, "reason", "bonus"))))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        final long id = mapper.readTree(response).path("data").path("id").asLong();
        mvc.perform(get("/points/" + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(100));
        mvc.perform(get("/points/leaderboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.entries").isArray());
        assertThat(redis.opsForValue().get("points::" + user)).isEqualTo("100");
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isEqualTo(100.0);
        mvc.perform(put("/points/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reason").value("updated"));
        assertThat(jdbc.queryForObject("SELECT reason FROM point_record WHERE id=?", String.class, id))
                .isEqualTo("updated");
        final String eventId = jdbc.queryForObject(
                "SELECT event_id FROM points_outbox WHERE user_id=? AND event_type='POINTS_ADDED'", String.class, user);
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            for (final String pending : outbox.pendingIds(50)) {
                worker.process(pending);
            }
            assertThat(jdbc.queryForObject(
                            "SELECT delivered FROM points_outbox WHERE event_id=?", Boolean.class, eventId))
                    .isTrue();
        });
        assertBrokerContains(eventId);
        verify(publisher, times(1)).publish(any());
        mvc.perform(delete("/points/" + user)).andExpect(status().isOk());
        verify(publisher, times(1)).publish(any());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM points_outbox WHERE user_id=? AND event_type='POINTS_DELETED'",
                        Long.class,
                        user))
                .isZero();
        assertThat(redis.opsForValue().get("points::" + user)).isNull();
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE user_id=?", Long.class, user))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM point_record WHERE user_id=? AND deleted_at IS NOT NULL",
                        Long.class,
                        user))
                .isEqualTo(1);
        mvc.perform(get("/points/" + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mvc.perform(put("/points/" + id).contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"changed\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.data.code").value("PTS0002"));
        assertThat(jdbc.queryForObject("SELECT reason FROM point_record WHERE id=?", String.class, id))
                .isEqualTo("updated");

        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("userId", user, "amount", 20, "reason", "new"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/points/" + user))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(20));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE user_id=?", Long.class, user))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM point_record WHERE user_id=? AND deleted_at IS NULL", Long.class, user))
                .isEqualTo(1);
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isEqualTo(20.0);
    }

    @Test
    void concurrentMysqlAddsKeepEveryRecordAndLatestLeaderboardScore() throws Exception {
        int committed = 0;
        try (final ExecutorService pool = Executors.newFixedThreadPool(4)) {
            final List<Future<?>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(pool.submit(() -> service.add(new AddPointsCommand(user, 5, "parallel"))));
            }
            for (final Future<?> result : results) {
                try {
                    result.get(30, TimeUnit.SECONDS);
                    committed++;
                } catch (final ExecutionException exception) {
                    assertThat(exception.getCause()).isInstanceOf(OptimisticLockingFailureException.class);
                }
            }
        }
        assertThat(committed).isBetween(1, 12);
        assertThat(service.total(new GetPointsQuery(user)).total()).isEqualTo(committed * 5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM point_record WHERE user_id=?", Long.class, user))
                .isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox WHERE user_id=?", Long.class, user))
                .isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account WHERE user_id=?", Long.class, user))
                .isEqualTo(committed);
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isEqualTo(committed * 5.0);
    }

    @Test
    void concurrentMysqlAddAndDeleteUseOptimisticRetryAndKeepLeaderboardConsistent() throws Exception {
        service.add(new AddPointsCommand(user, 100, "seed"));
        final CountDownLatch ready = new CountDownLatch(2);
        final AtomicInteger reads = new AtomicInteger();
        final JpaPointsRepository target = AopTestUtils.getUltimateTargetObject(repository);
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
                .when(target)
                .findAccount(user);

        try (final ExecutorService pool = Executors.newFixedThreadPool(2)) {
            final Future<?> add = pool.submit(() -> service.add(new AddPointsCommand(user, 5, "parallel")));
            final Future<?> delete = pool.submit(() -> service.delete(new DeletePointsCommand(user)));
            add.get(15, TimeUnit.SECONDS);
            delete.get(15, TimeUnit.SECONDS);
        }

        final long total = service.total(new GetPointsQuery(user)).total();
        assertThat(total).isIn(0L, 5L);
        assertThat(jdbc.queryForObject(
                        "SELECT COALESCE(SUM(amount),0) FROM point_record WHERE user_id=? AND deleted_at IS NULL",
                        Long.class,
                        user))
                .isEqualTo(total);
        assertThat(jdbc.queryForObject("SELECT version FROM points_account WHERE user_id=?", Long.class, user))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM points_outbox WHERE user_id=?", Long.class, user))
                .isEqualTo(2);
        if (total == 0) {
            assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                    .isNull();
        } else {
            assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                    .isEqualTo((double) total);
        }
    }

    @Test
    void deletionSkipsBrokerAndDelayedAddCannotRestoreLeaderboard() {
        doThrow(new SystemException(ErrorCode.MESSAGE_ERROR, new IllegalStateException("simulated broker outage")))
                .doCallRealMethod()
                .when(publisher)
                .publish(any());
        service.add(new AddPointsCommand(user, 100, "pending-add"));
        final String addEventId = jdbc.queryForObject(
                "SELECT event_id FROM points_outbox WHERE user_id=? AND event_type='POINTS_ADDED'", String.class, user);
        assertThat(outbox.pendingIds(50)).contains(addEventId);

        service.delete(new DeletePointsCommand(user));
        verify(publisher, times(1)).publish(any());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM points_outbox WHERE user_id=? AND event_type='POINTS_DELETED'",
                        Long.class,
                        user))
                .isZero();
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isNull();

        assertThat(worker.process(addEventId)).isTrue();
        assertThat(outbox.pendingIds(50)).doesNotContain(addEventId);
        assertThat(redis.opsForZSet().score(RedisLeaderboardRepository.SCORES, user))
                .isNull();
        assertThat(service.total(new GetPointsQuery(user)).total()).isZero();
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void brokerFailureAfterCommitKeepsHttpWriteSuccessfulAndRetryable() throws Exception {
        doThrow(new SystemException(ErrorCode.MESSAGE_ERROR, new IllegalStateException("simulated broker outage")))
                .doCallRealMethod()
                .when(publisher)
                .publish(any());
        mvc.perform(post("/points")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                Map.of("userId", user, "amount", 100, "reason", "outage-test"))))
                .andExpect(status().isCreated());
        final String eventId =
                jdbc.queryForObject("SELECT event_id FROM points_outbox WHERE user_id=?", String.class, user);
        assertThat(outbox.pendingIds(50)).contains(eventId);
        assertThat(service.total(new GetPointsQuery(user)).total()).isEqualTo(100);
        assertThat(worker.process(eventId)).isTrue();
        assertThat(outbox.pendingIds(50)).doesNotContain(eventId);
        assertBrokerContains(eventId);
    }

    @SuppressWarnings("deprecation")
    private void assertBrokerContains(final String eventId) throws Exception {
        final DefaultMQPullConsumer consumer = new DefaultMQPullConsumer("points-it-" + UUID.randomUUID());
        consumer.setNamesrvAddr("localhost:9876");
        consumer.setVipChannelEnabled(false);
        consumer.start();
        try {
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                for (final MessageQueue queue : consumer.fetchSubscribeMessageQueues("user-points-topic")) {
                    long offset = consumer.minOffset(queue);
                    final long maximum = consumer.maxOffset(queue);
                    while (offset < maximum) {
                        final PullResult result = consumer.pull(queue, "*", offset, 32);
                        if (result.getMsgFoundList() != null) {
                            for (final MessageExt message : result.getMsgFoundList()) {
                                if (eventId.equals(message.getKeys())) {
                                    return true;
                                }
                            }
                        }
                        if (result.getNextBeginOffset() <= offset) {
                            break;
                        }
                        offset = result.getNextBeginOffset();
                    }
                }
                return false;
            });
        } finally {
            consumer.shutdown();
        }
    }
}
