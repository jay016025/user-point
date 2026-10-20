package com.example.demo.persistence.points;

import java.util.List;
import java.util.Optional;

import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;

/** Domain-facing persistence port. Entity and Spring Data types stay inside the persistence adapter. */
public interface PointsRepository {
    Optional<PointsAccount> findAccount(String userId);

    PointsAccount findOrCreateAccount(String userId);

    long findTotal(String userId);

    /** All accounts ordered by total and user ID descending, including zero-balance revision markers. */
    List<PointsAccount> findLeaderboardSnapshot();

    PointRecord insert(PointRecord record);

    /** Save using the current revision and return the account with its persisted event version. */
    PointsAccount saveAccount(PointsAccount account);

    Optional<PointRecord> findRecord(long id);

    boolean updateReason(long id, String reason);

    /** Mark active records as deleted while retaining their history. */
    void deleteRecords(String userId);

    void enqueue(PointsChanged event);
}
