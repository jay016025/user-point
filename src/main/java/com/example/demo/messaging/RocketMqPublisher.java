package com.example.demo.messaging;

import org.apache.rocketmq.client.exception.MQBrokerException;
import org.apache.rocketmq.client.exception.MQClientException;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.remoting.exception.RemotingException;

import com.example.demo.domain.PointsChanged;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;
import com.example.demo.messaging.mapper.PointsMessageMapper;
import com.example.demo.model.message.PointsUpdatedMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class RocketMqPublisher implements MessagePublisher {
    private final DefaultMQProducer producer;
    private final ObjectMapper mapper;
    private final String topic;
    private final PointsMessageMapper messageMapper;

    public RocketMqPublisher(
            final DefaultMQProducer producer,
            final ObjectMapper mapper,
            final PointsMessageMapper messageMapper,
            final String topic) {
        this.producer = producer;
        this.mapper = mapper;
        this.topic = topic;
        this.messageMapper = messageMapper;
    }

    @Override
    public void publish(final PointsChanged event) {
        final PointsUpdatedMessage payload = messageMapper.message(event);
        try {
            // Use synchronous RocketMQ send: only SEND_OK counts as acknowledged delivery.
            // The stable key supports consumer deduplication; RocketMQ does not automatically deduplicate by key.
            final Message message = new Message(topic, mapper.writeValueAsBytes(payload));
            message.setKeys(event.eventId());
            message.setTags(payload.type().name());
            final SendResult result = producer.send(message);
            if (result == null || result.getSendStatus() != SendStatus.SEND_OK) {
                throw new SystemException(
                        ErrorCode.MESSAGE_ERROR, new IllegalStateException("Broker did not acknowledge SEND_OK"));
            }
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SystemException(ErrorCode.MESSAGE_ERROR, exception);
        } catch (final MQClientException | MQBrokerException | RemotingException | JsonProcessingException exception) {
            throw new SystemException(ErrorCode.MESSAGE_ERROR, exception);
        }
    }
}
