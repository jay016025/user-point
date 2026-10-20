package com.example.demo.messaging.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ValueMapping;

import com.example.demo.config.MappingConfiguration;
import com.example.demo.domain.PointsChanged;
import com.example.demo.domain.PointsEventType;
import com.example.demo.model.message.PointsMessageType;
import com.example.demo.model.message.PointsUpdatedMessage;

@Mapper(config = MappingConfiguration.class)
public interface PointsMessageMapper {
    PointsUpdatedMessage message(PointsChanged event);

    // Deletion is an internal Redis cleanup event and has no public MQ representation.
    @ValueMapping(source = "POINTS_DELETED", target = MappingConstants.THROW_EXCEPTION)
    PointsMessageType messageType(PointsEventType type);
}
