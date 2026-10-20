package com.example.demo.controller.response;

public record ErrorResponse(String code, String message, String traceId) {}
