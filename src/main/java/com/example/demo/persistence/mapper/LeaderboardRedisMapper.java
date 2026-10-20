package com.example.demo.persistence.mapper;

import java.util.Objects;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.springframework.data.redis.core.ZSetOperations;

import com.example.demo.config.MappingConfiguration;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.model.redis.LeaderboardUpdate;

@Mapper(config = MappingConfiguration.class)
public interface LeaderboardRedisMapper {
    @Mapping(target = "userId", source = "value")
    @Mapping(target = "total", source = "score", qualifiedByName = "scoreToLong")
    @Mapping(target = "version", constant = "0L")
    PointsAccount account(ZSetOperations.TypedTuple<String> tuple);

    LeaderboardUpdate update(PointsChanged event);

    LeaderboardUpdate snapshot(PointsAccount account);

    @Mapping(target = "total", constant = "0L")
    LeaderboardUpdate removal(PointsAccount account);

    @Named("scoreToLong")
    default long scoreToLong(final Double score) {
        return Objects.requireNonNull(score).longValue();
    }
}
