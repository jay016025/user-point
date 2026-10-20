package com.example.demo.config;

import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.example.demo.messaging.MessagePublisher;
import com.example.demo.messaging.RocketMqPublisher;
import com.example.demo.messaging.mapper.PointsMessageMapper;
import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "rocketmq.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RocketMqConfiguration {
    @Bean(initMethod = "start", destroyMethod = "shutdown")
    public DefaultMQProducer pointsProducer(
            @Value("${rocketmq.name-server}") final String nameserver,
            @Value("${rocketmq.producer-group}") final String group,
            @Value("${rocketmq.send-timeout-ms:3000}") final int timeout) {
        // Share one producer for the application lifetime; never create a producer for every HTTP request.
        final DefaultMQProducer producer = new DefaultMQProducer(group);
        producer.setNamesrvAddr(nameserver);
        producer.setSendMsgTimeout(timeout);
        producer.setRetryTimesWhenSendFailed(2);
        producer.setVipChannelEnabled(false);
        return producer;
    }

    @Bean
    public MessagePublisher messagePublisher(
            final DefaultMQProducer producer,
            final ObjectMapper mapper,
            final PointsMessageMapper messageMapper,
            @Value("${rocketmq.topic}") final String topic) {
        return new RocketMqPublisher(producer, mapper, messageMapper, topic);
    }
}
