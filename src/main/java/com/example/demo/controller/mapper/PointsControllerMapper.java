package com.example.demo.controller.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.example.demo.config.MappingConfiguration;
import com.example.demo.controller.request.AddPointsRequest;
import com.example.demo.controller.request.UpdateReasonRequest;
import com.example.demo.controller.response.AddPointsResponse;
import com.example.demo.controller.response.DeletePointsResponse;
import com.example.demo.controller.response.GetLeaderboardResponse;
import com.example.demo.controller.response.GetPointsResponse;
import com.example.demo.controller.response.LeaderboardEntryResponse;
import com.example.demo.controller.response.UpdateReasonResponse;
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
public interface PointsControllerMapper {
    AddPointsCommand command(AddPointsRequest request);

    @Mapping(target = "userId", source = "userId")
    GetPointsQuery query(String userId);

    @Mapping(target = "userId", source = "userId")
    DeletePointsCommand command(String userId);

    @Mapping(target = "id", source = "id")
    @Mapping(target = "reason", source = "request.reason")
    UpdateReasonCommand command(long id, UpdateReasonRequest request);

    AddPointsResponse added(AddPointsResult result);

    GetPointsResponse total(GetPointsResult result);

    UpdateReasonResponse updated(UpdateReasonResult result);

    DeletePointsResponse deleted(DeletePointsResult result);

    LeaderboardEntryResponse entry(LeaderboardEntryResult entry);

    GetLeaderboardResponse leaderboard(GetLeaderboardResult result);
}
