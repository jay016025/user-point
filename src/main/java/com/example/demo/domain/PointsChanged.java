package com.example.demo.domain;

import java.time.Instant;

public record PointsChanged(
        String eventId,
        PointsEventType type,
        String userId,
        long recordId,
        long amount,
        long total,
        long version,
        String reason,
        Instant occurredAt) {}
