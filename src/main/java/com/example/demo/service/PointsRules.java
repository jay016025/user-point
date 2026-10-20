package com.example.demo.service;

import com.example.demo.exception.BusinessException;
import com.example.demo.exception.ErrorCode;

public final class PointsRules {
    public static final long MAX_TOTAL = 9007199254740991L;
    public static final String USER_ID_PATTERN = "(?!leaderboard$)[A-Za-z0-9_-]{1,64}";

    private PointsRules() {}

    public static void userId(final String value) {
        if (value == null || !value.matches(USER_ID_PATTERN)) {
            invalid();
        }
    }

    public static void reason(final String value) {
        if (value == null || value.isBlank() || value.length() > 255) {
            invalid();
        }
    }

    public static void invalid() {
        throw new BusinessException(ErrorCode.INVALID_REQUEST);
    }
}
