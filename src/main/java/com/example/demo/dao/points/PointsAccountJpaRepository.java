package com.example.demo.dao.points;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.QueryHint;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.example.demo.model.entity.points.PointsAccountEntity;

public interface PointsAccountJpaRepository extends JpaRepository<PointsAccountEntity, String> {
    @Query("SELECT a FROM PointsAccountEntity a ORDER BY a.total DESC, a.userId DESC")
    List<PointsAccountEntity> findLeaderboardSnapshot();

    // A repeated deletion must invalidate older additions even when total is already zero.
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            "UPDATE PointsAccountEntity a SET a.version = a.version + 1 WHERE a.userId = :userId AND a.version = :version")
    int advanceVersion(@Param("userId") String userId, @Param("version") long version);

    @Query("SELECT a.total FROM PointsAccountEntity a WHERE a.userId = :userId")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    Optional<Long> findTotal(@Param("userId") String userId);
}
