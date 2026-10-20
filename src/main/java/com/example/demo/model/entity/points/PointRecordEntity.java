package com.example.demo.model.entity.points;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "point_record")
@Getter
@Setter
@NoArgsConstructor
public class PointRecordEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 255)
    private String reason;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
