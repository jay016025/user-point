package com.example.demo.model.entity.outbox;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "points_outbox")
@Getter
@Setter
@NoArgsConstructor
public class PointsOutboxEntity {
    @Id
    @Column(name = "event_id", length = 36)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 32)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private OutboxEventType eventType;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(name = "record_id", nullable = false)
    private long recordId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private long total;

    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 255)
    private String reason;

    @JdbcTypeCode(SqlTypes.TIMESTAMP)
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private boolean delivered;
}
