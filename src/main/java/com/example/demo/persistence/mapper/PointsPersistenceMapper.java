package com.example.demo.persistence.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;

import com.example.demo.config.MappingConfiguration;
import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsChanged;
import com.example.demo.model.entity.outbox.PointsOutboxEntity;
import com.example.demo.model.entity.points.PointRecordEntity;
import com.example.demo.model.entity.points.PointsAccountEntity;

@Mapper(config = MappingConfiguration.class)
public interface PointsPersistenceMapper {
    PointsAccount account(PointsAccountEntity entity);

    @Mapping(target = "userId", source = "userId")
    @Mapping(target = "total", constant = "0L")
    @Mapping(target = "version", ignore = true)
    PointsAccountEntity newAccount(String userId);

    @Mapping(target = "userId", ignore = true)
    @Mapping(target = "version", ignore = true)
    void updateAccount(PointsAccount account, @MappingTarget PointsAccountEntity entity);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "deletedAt", ignore = true)
    PointRecordEntity newRecord(PointRecord record);

    PointRecord record(PointRecordEntity entity);

    @Mapping(target = "eventType", source = "type")
    @Mapping(target = "delivered", constant = "false")
    PointsOutboxEntity outbox(PointsChanged event);

    @Mapping(target = "type", source = "eventType")
    PointsChanged event(PointsOutboxEntity entity);
}
