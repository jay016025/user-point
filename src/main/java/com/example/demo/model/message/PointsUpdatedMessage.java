package com.example.demo.model.message;

import java.time.Instant;

public record PointsUpdatedMessage(
        String eventId,
        PointsMessageType type,
        String userId,
        long recordId,
        long amount,
        long total,
        long version,
        String reason,
        Instant occurredAt) {}
