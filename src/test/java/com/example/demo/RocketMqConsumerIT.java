package com.example.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.consumer.ConsumeFromWhere;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.example.demo.model.message.PointsMessageType;
import com.example.demo.model.message.PointsUpdatedMessage;
import com.example.demo.persistence.leaderboard.RedisLeaderboardRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = "points.outbox.delay-ms=60000")
@AutoConfigureMockMvc
class RocketMqConsumerIT {
    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Value("${rocketmq.name-server}")
    private String nameserver;

    @Value("${rocketmq.topic}")
    private String topic;

    private final String userId = "consumer_it_" + UUID.randomUUID().toString().replace("-", "");

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM points_outbox WHERE user_id=?", userId);
        jdbc.update("DELETE FROM point_record WHERE user_id=?", userId);
        jdbc.update("DELETE FROM points_account WHERE user_id=?", userId);
        redis.delete("points::" + userId);
        redis.opsForZSet().remove(RedisLeaderboardRepository.SCORES, userId);
        redis.opsForHash().delete(RedisLeaderboardRepository.VERSIONS, userId);
    }

    @Test
    void consumerReceivesMessagePublishedThroughAddPointsApi() throws Exception {
        final CompletableFuture<MessageExt> received = new CompletableFuture<>();
        final DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("points-consumer-it-" + UUID.randomUUID());
        consumer.setNamesrvAddr(nameserver);
        consumer.setVipChannelEnabled(false);
        consumer.setConsumeThreadMin(1);
        consumer.setConsumeThreadMax(1);
        consumer.setConsumeMessageBatchMaxSize(1);
        // Include retained messages so a first rebalance cannot skip our newly published event.
        consumer.setConsumeFromWhere(ConsumeFromWhere.CONSUME_FROM_FIRST_OFFSET);
        consumer.subscribe(topic, PointsMessageType.POINTS_ADDED.name());
        consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
            for (final MessageExt message : messages) {
                try {
                    final JsonNode body = mapper.readTree(message.getBody());
                    if (userId.equals(body.path("userId").asText())) {
                        received.complete(message);
                    }
                } catch (final IOException exception) {
                    received.completeExceptionally(exception);
                    return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                }
            }
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        });

        try {
            consumer.start();
            final String response = mvc.perform(post("/points")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(
                                    Map.of("userId", userId, "amount", 100, "reason", "consumer_test"))))
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            final long recordId =
                    mapper.readTree(response).path("data").path("id").asLong();
            final String eventId =
                    jdbc.queryForObject("SELECT event_id FROM points_outbox WHERE user_id=?", String.class, userId);

            final MessageExt message = received.get(30, TimeUnit.SECONDS);
            final PointsUpdatedMessage payload = mapper.readValue(message.getBody(), PointsUpdatedMessage.class);
            assertThat(message.getTopic()).isEqualTo(topic);
            assertThat(message.getTags()).isEqualTo(PointsMessageType.POINTS_ADDED.name());
            assertThat(message.getMsgId()).isNotBlank();
            assertThat(message.getKeys()).isEqualTo(eventId);
            assertThat(payload.eventId()).isEqualTo(eventId);
            assertThat(payload.type()).isEqualTo(PointsMessageType.POINTS_ADDED);
            assertThat(payload.userId()).isEqualTo(userId);
            assertThat(payload.recordId()).isEqualTo(recordId);
            assertThat(payload.amount()).isEqualTo(100);
            assertThat(payload.total()).isEqualTo(100);
            assertThat(payload.version()).isEqualTo(1);
            assertThat(payload.reason()).isEqualTo("consumer_test");
            assertThat(payload.occurredAt()).isNotNull();
            assertThat(jdbc.queryForObject(
                            "SELECT delivered FROM points_outbox WHERE event_id=?", Boolean.class, eventId))
                    .isTrue();
            System.out.printf(
                    "Received RocketMQ message: topic=%s, msgId=%s, eventId=%s%n%s%n",
                    message.getTopic(),
                    message.getMsgId(),
                    payload.eventId(),
                    new String(message.getBody(), StandardCharsets.UTF_8));
        } finally {
            consumer.shutdown();
        }
    }
}
