package com.example.demo.controller.response;

public record ApiResponse<T>(boolean success, T data) {
    public static <T> ApiResponse<T> ok(final T data) {
        return new ApiResponse<>(true, data);
    }

    public static <T> ApiResponse<T> error(final T data) {
        return new ApiResponse<>(false, data);
    }
}
