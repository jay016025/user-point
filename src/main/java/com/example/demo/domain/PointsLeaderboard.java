package com.example.demo.domain;

import java.util.List;

public record PointsLeaderboard(List<PointsAccount> entries) {
    public PointsLeaderboard {
        entries = List.copyOf(entries);
    }
}
