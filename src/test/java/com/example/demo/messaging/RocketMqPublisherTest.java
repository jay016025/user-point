package com.example.demo.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;

import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.messaging.mapper.PointsMessageMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;

class RocketMqPublisherTest {
    private final DefaultMQProducer producer = mock(DefaultMQProducer.class);
    private final RocketMqPublisher publisher = new RocketMqPublisher(
            producer,
            JsonMapper.builder().findAndAddModules().build(),
            Mappers.getMapper(PointsMessageMapper.class),
            "user-points-topic");
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
    void sendsJsonToRequiredTopicWithStableDeduplicationKey() throws Exception {
        final SendResult result = new SendResult();
        result.setSendStatus(SendStatus.SEND_OK);
        when(producer.send(any(Message.class))).thenReturn(result);
        publisher.publish(event);
        final ArgumentCaptor<Message> message = ArgumentCaptor.forClass(Message.class);
        verify(producer).send(message.capture());
        assertThat(message.getValue().getTopic()).isEqualTo("user-points-topic");
        assertThat(message.getValue().getKeys()).isEqualTo("event-id");
        final JsonNode payload =
                JsonMapper.builder().build().readTree(message.getValue().getBody());
        assertThat(payload.get("total").asLong()).isEqualTo(120);
        assertThat(payload.get("eventId").asText()).isEqualTo("event-id");
    }

    @Test
    void internalDeletionCannotBeMappedToPublicRocketMqMessage() {
        final PointsChanged deletion = new PointsChanged(
                "delete-id", PointsEventType.POINTS_DELETED, "user_1", 0, 0, 0, 5, "", event.occurredAt());

        assertThatThrownBy(() -> publisher.publish(deletion)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(producer);
    }

    @Test
    void unsuccessfulBrokerAcknowledgementIsSystemError() throws Exception {
        final SendResult result = new SendResult();
        result.setSendStatus(SendStatus.FLUSH_DISK_TIMEOUT);
        when(producer.send(any(Message.class))).thenReturn(result);
        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOfSatisfying(
                        SystemException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.MESSAGE_ERROR));
    }

    @Test
    void interruptionPreservesThreadInterruptFlag() throws Exception {
        when(producer.send(any(Message.class))).thenThrow(new InterruptedException("interrupted"));
        try {
            assertThatThrownBy(() -> publisher.publish(event)).isInstanceOf(SystemException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
