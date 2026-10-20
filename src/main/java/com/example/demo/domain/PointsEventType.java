package com.example.demo.domain;

public enum PointsEventType {
    POINTS_ADDED,
    // Retained only to read historical outbox rows; new deletions do not emit events.
    POINTS_DELETED
}
