package com.example.demo.persistence.outbox;

import java.util.List;
import java.util.Optional;

import com.example.demo.domain.PointsChanged;

public interface OutboxRepository {
    List<String> pendingIds(int limit);

    Optional<PointsChanged> lockPending(String eventId);

    void markDelivered(String eventId);
}
