package com.example.demo.exception;

public enum ErrorCode {
    INVALID_REQUEST("PTS0001", "請求參數不正確"),
    RECORD_NOT_FOUND("PTS0002", "找不到積分紀錄"),
    POINTS_LIMIT_EXCEEDED("PTS0003", "積分總額超過上限"),
    CONCURRENT_MODIFICATION("PTS0004", "加入積分失敗，請稍後重試"),
    SYSTEM_ERROR("SYS0001", "系統暫時無法處理請求"),
    DATABASE_ERROR("SYS0002", "資料庫暫時無法處理請求"),
    CACHE_ERROR("SYS0003", "快取服務暫時無法處理請求"),
    MESSAGE_ERROR("SYS0004", "訊息服務暫時無法處理請求"),
    RESOURCE_NOT_FOUND("SYS0005", "找不到指定資源"),
    METHOD_NOT_ALLOWED("SYS0006", "不支援此 HTTP 方法"),
    UNSUPPORTED_MEDIA_TYPE("SYS0007", "不支援此內容格式");
    private final String code;
    private final String message;

    ErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }
}
