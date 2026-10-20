package com.example.demo.exception;

import java.util.Objects;

public class SystemException extends RuntimeException {
    private final ErrorCode errorCode;

    public SystemException(final ErrorCode errorCode, final Throwable cause) {
        super(Objects.requireNonNull(errorCode).message(), cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
