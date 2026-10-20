package com.example.demo.model.entity.points;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "points_account")
@Getter
@Setter
@NoArgsConstructor
public class PointsAccountEntity {
    @Id
    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private long total;

    // Shared optimistic-lock revision and outbox/leaderboard event sequence.
    @Version
    @Column(nullable = false)
    private Long version;
}
