package com.example.demo.service;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

import com.example.demo.persistence.points.PointsRepository;

/** Separate bean ensures the Spring Cache proxy is applied to service-to-service calls. */
@Component
public class PointsTotalReader {
    public static final String CACHE = "points";
    private final PointsRepository repository;

    public PointsTotalReader(final PointsRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE, key = "#userId", sync = true)
    public long total(final String userId) {
        return repository.findTotal(userId);
    }
}
