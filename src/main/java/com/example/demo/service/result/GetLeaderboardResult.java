package com.example.demo.service.result;

import java.util.List;

public record GetLeaderboardResult(List<LeaderboardEntryResult> entries) {
    public GetLeaderboardResult {
        entries = List.copyOf(entries);
    }
}
