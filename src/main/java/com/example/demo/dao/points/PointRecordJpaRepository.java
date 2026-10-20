package com.example.demo.dao.points;

import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.demo.model.entity.points.PointRecordEntity;

public interface PointRecordJpaRepository extends JpaRepository<PointRecordEntity, Long> {
    Optional<PointRecordEntity> findByIdAndDeletedAtIsNull(long id);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PointRecordEntity r SET r.reason = :reason WHERE r.id = :id AND r.deletedAt IS NULL")
    int updateReason(@Param("id") long id, @Param("reason") String reason);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE PointRecordEntity r SET r.deletedAt = :deletedAt WHERE r.userId = :userId AND r.deletedAt IS NULL")
    int softDeleteRecords(@Param("userId") String userId, @Param("deletedAt") Instant deletedAt);
}
