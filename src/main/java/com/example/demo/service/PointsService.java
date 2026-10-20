package com.example.demo.service;

import com.example.demo.service.command.AddPointsCommand;
import com.example.demo.service.command.DeletePointsCommand;
import com.example.demo.service.command.UpdateReasonCommand;
import com.example.demo.service.query.GetPointsQuery;
import com.example.demo.service.result.AddPointsResult;
import com.example.demo.service.result.DeletePointsResult;
import com.example.demo.service.result.GetLeaderboardResult;
import com.example.demo.service.result.GetPointsResult;
import com.example.demo.service.result.UpdateReasonResult;

public interface PointsService {
    AddPointsResult add(AddPointsCommand command);

    GetPointsResult total(GetPointsQuery query);

    GetLeaderboardResult leaderboard();

    UpdateReasonResult updateReason(UpdateReasonCommand command);

    DeletePointsResult delete(DeletePointsCommand command);
}
