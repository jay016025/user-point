package com.example.demo.controller.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.example.demo.service.PointsRules;

public record AddPointsRequest(
        @NotBlank @Pattern(regexp = PointsRules.USER_ID_PATTERN) String userId,
        @NotNull @Min(1) @Max(PointsRules.MAX_TOTAL) Long amount,
        @NotBlank @Size(max = 255) String reason) {}
