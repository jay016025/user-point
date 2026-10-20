package com.example.demo.messaging;

import com.example.demo.domain.PointsChanged;

public interface MessagePublisher {
    void publish(PointsChanged event);
}
