package com.example.demo.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.example.demo.controller.mapper.PointsControllerMapper;
import com.example.demo.controller.request.AddPointsRequest;
import com.example.demo.controller.request.UpdateReasonRequest;
import com.example.demo.controller.response.AddPointsResponse;
import com.example.demo.controller.response.ApiResponse;
import com.example.demo.controller.response.DeletePointsResponse;
import com.example.demo.controller.response.GetLeaderboardResponse;
import com.example.demo.controller.response.GetPointsResponse;
import com.example.demo.controller.response.UpdateReasonResponse;
import com.example.demo.service.PointsRules;
import com.example.demo.service.PointsService;

@RestController
@RequestMapping("/points")
public class PointsController {
    private final PointsService service;
    private final PointsControllerMapper mapper;

    public PointsController(final PointsService service, final PointsControllerMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AddPointsResponse> add(@Valid @RequestBody final AddPointsRequest request) {
        return ApiResponse.ok(mapper.added(service.add(mapper.command(request))));
    }

    @GetMapping("/leaderboard")
    public ApiResponse<GetLeaderboardResponse> leaderboard() {
        return ApiResponse.ok(mapper.leaderboard(service.leaderboard()));
    }

    @GetMapping("/{userId}")
    public ApiResponse<GetPointsResponse> total(
            @PathVariable("userId") @NotBlank @Pattern(regexp = PointsRules.USER_ID_PATTERN) final String userId) {
        return ApiResponse.ok(mapper.total(service.total(mapper.query(userId))));
    }

    @PutMapping("/{id}")
    public ApiResponse<UpdateReasonResponse> updateReason(
            @PathVariable("id") @Positive final long id, @Valid @RequestBody final UpdateReasonRequest request) {
        return ApiResponse.ok(mapper.updated(service.updateReason(mapper.command(id, request))));
    }

    @DeleteMapping("/{userId}")
    public ApiResponse<DeletePointsResponse> delete(
            @PathVariable("userId") @NotBlank @Pattern(regexp = PointsRules.USER_ID_PATTERN) final String userId) {
        return ApiResponse.ok(mapper.deleted(service.delete(mapper.command(userId))));
    }
}
