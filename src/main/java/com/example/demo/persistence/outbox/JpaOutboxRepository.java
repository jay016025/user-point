package com.example.demo.persistence.outbox;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.dao.outbox.PointsOutboxJpaRepository;
import com.example.demo.domain.PointsChanged;
import com.example.demo.model.entity.outbox.PointsOutboxEntity;
import com.example.demo.persistence.mapper.PointsPersistenceMapper;

@Repository
public class JpaOutboxRepository implements OutboxRepository {
    private final PointsOutboxJpaRepository outbox;
    private final PointsPersistenceMapper mapper;

    public JpaOutboxRepository(final PointsOutboxJpaRepository outbox, final PointsPersistenceMapper mapper) {
        this.outbox = outbox;
        this.mapper = mapper;
    }

    @Override
    public List<String> pendingIds(final int limit) {
        return List.copyOf(outbox.pendingIds(PageRequest.of(0, limit)));
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<PointsChanged> lockPending(final String eventId) {
        return outbox.lockPending(eventId).map(mapper::event);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void markDelivered(final String eventId) {
        final PointsOutboxEntity entity = outbox.findById(eventId).orElseThrow();
        entity.setDelivered(true);
    }
}
