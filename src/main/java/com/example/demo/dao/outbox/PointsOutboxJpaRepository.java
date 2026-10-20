package com.example.demo.dao.outbox;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

import com.example.demo.model.entity.outbox.PointsOutboxEntity;

public interface PointsOutboxJpaRepository extends JpaRepository<PointsOutboxEntity, String> {
    @Query("SELECT e.eventId FROM PointsOutboxEntity e WHERE e.delivered = false ORDER BY e.occurredAt, e.eventId")
    List<String> pendingIds(Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PointsOutboxEntity e WHERE e.eventId = :eventId AND e.delivered = false")
    @QueryHints(@QueryHint(name = "jakarta.persistence.query.timeout", value = "5000"))
    Optional<PointsOutboxEntity> lockPending(@Param("eventId") String eventId);
}
