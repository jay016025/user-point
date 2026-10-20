package com.example.demo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

@Configuration
// Retry wraps the transaction, so every attempt starts after the failed transaction has rolled back.
@EnableRetry(proxyTargetClass = true)
public class PointsRetryConfiguration {}
