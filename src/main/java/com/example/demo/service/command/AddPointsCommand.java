package com.example.demo.service.command;

public record AddPointsCommand(String userId, long amount, String reason) {}
