package com.example.demo.controller.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateReasonRequest(@NotBlank @Size(max = 255) String reason) {}
