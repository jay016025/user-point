package com.example.demo.controller.advice;

import java.util.UUID;

import jakarta.persistence.OptimisticLockException;
import jakarta.persistence.PersistenceException;
import jakarta.validation.ConstraintViolationException;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.example.demo.controller.response.ApiResponse;
import com.example.demo.controller.response.ErrorResponse;
import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;
import com.example.demo.exception.SystemException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> business(final BusinessException exception) {
        final HttpStatus status =
                switch (exception.errorCode()) {
                    case RECORD_NOT_FOUND -> HttpStatus.NOT_FOUND;
                    case POINTS_LIMIT_EXCEEDED -> HttpStatus.CONFLICT;
                    default -> HttpStatus.BAD_REQUEST;
                };
        return response(exception.errorCode(), status, UUID.randomUUID().toString());
    }

    @ExceptionHandler(SystemException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> system(final SystemException exception) {
        final HttpStatus status =
                switch (exception.errorCode()) {
                    case DATABASE_ERROR, CACHE_ERROR, MESSAGE_ERROR -> HttpStatus.SERVICE_UNAVAILABLE;
                    default -> HttpStatus.INTERNAL_SERVER_ERROR;
                };
        return serverError(exception.errorCode(), status, exception);
    }

    @ExceptionHandler({OptimisticLockingFailureException.class, OptimisticLockException.class})
    public ResponseEntity<ApiResponse<ErrorResponse>> optimisticConflict(final RuntimeException exception) {
        return response(
                ErrorCode.CONCURRENT_MODIFICATION,
                HttpStatus.CONFLICT,
                UUID.randomUUID().toString());
    }

    @ExceptionHandler({DataAccessException.class, PersistenceException.class, TransactionException.class})
    public ResponseEntity<ApiResponse<ErrorResponse>> database(final RuntimeException exception) {
        // Also covers transaction-commit errors raised outside the repository method.
        return serverError(ErrorCode.DATABASE_ERROR, HttpStatus.SERVICE_UNAVAILABLE, exception);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> validation(final ConstraintViolationException exception) {
        return response(
                ErrorCode.INVALID_REQUEST,
                HttpStatus.BAD_REQUEST,
                UUID.randomUUID().toString());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<ErrorResponse>> unexpected(final Exception exception) {
        return serverError(ErrorCode.SYSTEM_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, exception);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            final Exception exception,
            final Object body,
            final HttpHeaders headers,
            final HttpStatusCode status,
            final WebRequest request) {
        final ErrorCode code =
                switch (status.value()) {
                    case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
                    case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
                    case 415 -> ErrorCode.UNSUPPORTED_MEDIA_TYPE;
                    default -> status.is4xxClientError() ? ErrorCode.INVALID_REQUEST : ErrorCode.SYSTEM_ERROR;
                };
        final String traceId = UUID.randomUUID().toString();
        if (status.is5xxServerError()) {
            log.error("Request failed traceId={}", traceId, exception);
        } else {
            log.warn("Request rejected traceId={} code={}", traceId, code.code());
        }
        return new ResponseEntity<>(
                ApiResponse.error(new ErrorResponse(code.code(), code.message(), traceId)), headers, status);
    }

    private ResponseEntity<ApiResponse<ErrorResponse>> serverError(
            final ErrorCode code, final HttpStatus status, final Exception exception) {
        final String traceId = UUID.randomUUID().toString();
        log.error("Request failed traceId={} code={}", traceId, code.code(), exception);
        return response(code, status, traceId);
    }

    private ResponseEntity<ApiResponse<ErrorResponse>> response(
            final ErrorCode code, final HttpStatus status, final String traceId) {
        if (status.is4xxClientError()) {
            log.warn("Request rejected traceId={} code={}", traceId, code.code());
        }
        return ResponseEntity.status(status)
                .body(ApiResponse.error(new ErrorResponse(code.code(), code.message(), traceId)));
    }
}
