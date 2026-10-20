package com.example.demo.exception;

import java.util.Objects;

public class BusinessException extends RuntimeException {
    private final ErrorCode errorCode;

    public BusinessException(final ErrorCode errorCode) {
        super(Objects.requireNonNull(errorCode).message());
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
