package com.example.demo.controller.response;

import java.util.List;

public record GetLeaderboardResponse(List<LeaderboardEntryResponse> entries) {
    public GetLeaderboardResponse {
        entries = List.copyOf(entries);
    }
}
