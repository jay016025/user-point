package com.example.demo.persistence.leaderboard;

import java.util.List;

import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;

public interface LeaderboardRepository {
    List<PointsAccount> topTen();

    void apply(PointsChanged event);

    void remove(PointsAccount account);

    /** Batch-write the database snapshot into Redis scores and revision markers. */
    void restore(List<PointsAccount> accounts);
}
