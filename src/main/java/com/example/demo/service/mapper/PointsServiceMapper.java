package com.example.demo.service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.example.demo.config.MappingConfiguration;
import com.example.demo.domain.PointRecord;
import com.example.demo.domain.PointsAccount;
import com.example.demo.domain.PointsLeaderboard;
import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.AddPointsResult;
import com.example.demo.service.result.DeletePointsResult;
import com.example.demo.service.result.GetLeaderboardResult;
import com.example.demo.service.result.GetPointsResult;
import com.example.demo.service.result.LeaderboardEntryResult;
import com.example.demo.service.result.UpdateReasonResult;

@Mapper(config = MappingConfiguration.class)
public interface PointsServiceMapper {
    @Mapping(target = "id", constant = "0L")
    PointRecord newRecord(AddPointsCommand command);

    AddPointsResult added(PointRecord record);

    @Mapping(target = "userId", source = "query.userId")
    GetPointsResult total(GetPointsQuery query, long total);

    LeaderboardEntryResult entry(PointsAccount account);

    GetLeaderboardResult leaderboard(PointsLeaderboard leaderboard);

    @Mapping(target = "id", source = "record.id")
    @Mapping(target = "reason", source = "command.reason")
    PointRecord changedReason(PointRecord record, UpdateReasonCommand command);

    UpdateReasonResult updated(PointRecord record);

    DeletePointsResult deleted(DeletePointsCommand command);
}
