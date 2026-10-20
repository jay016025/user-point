package com.example.demo.model.redis;

public record LeaderboardUpdate(String userId, long total, long version) {}
