package com.example.demo.messaging;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.example.demo.domain.PointsChanged;
import com.example.demo.persistence.outbox.OutboxRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@EnableScheduling
@ConditionalOnProperty(name = "points.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxDispatcher {
    private final OutboxRepository repository;
    private final OutboxWorker worker;
    private final int batchSize;

    public OutboxDispatcher(
            final OutboxRepository repository,
            final OutboxWorker worker,
            @Value("${points.outbox.batch-size:50}") final int batchSize) {
        this.repository = repository;
        this.worker = worker;
        this.batchSize = batchSize;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(final PointsChanged event) {
        dispatch(event.eventId());
    }

    @Scheduled(
            fixedDelayString = "${points.outbox.delay-ms:1000}",
            initialDelayString = "${points.outbox.delay-ms:1000}")
    public void retryPending() {
        try {
            for (final String id : repository.pendingIds(batchSize)) {
                dispatch(id);
            }
        } catch (final RuntimeException exception) {
            log.error("Unable to read pending outbox events; next scheduled run will retry", exception);
        }
    }

    private void dispatch(final String id) {
        try {
            worker.process(id);
        } catch (final RuntimeException exception) {
            // The points transaction is already committed. Keep the outbox pending instead of reporting a false write
            // failure.
            log.error("Outbox dispatch failed eventId={}; retained for retry", id, exception);
        }
    }
}
