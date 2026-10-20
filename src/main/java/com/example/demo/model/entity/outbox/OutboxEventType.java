package com.example.demo.model.entity.outbox;

/** Stored names are a stable database contract. */
public enum OutboxEventType {
    POINTS_ADDED,
    POINTS_DELETED
}
