package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.demo.messaging.MessagePublisher;
import com.example.demo.persistence.leaderboard.LeaderboardRepository;

@SpringBootTest(
        properties = {
            "points.outbox.enabled=false",
            "rocketmq.enabled=false",
            "spring.datasource.url=jdbc:h2:mem:context;MODE=MySQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password="
        })
class DemoApplicationTests {
    @MockitoBean
    private MessagePublisher publisher;

    @MockitoBean
    private LeaderboardRepository leaderboard;

    @Test
    void contextLoads() {}
}
