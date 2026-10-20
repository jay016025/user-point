-- 僅供已執行 002、仍有 lock_version 的既有 MySQL 資料庫升級時執行一次。
-- 停止舊版應用程式後執行，再啟動新版；保留 version，不重設 outbox／Redis 的事件序號。
-- 新建資料庫直接使用 schema.sql 或 init.sql，不需執行此 migration。
ALTER TABLE points_account
    DROP COLUMN lock_version,
    MODIFY COLUMN version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 樂觀鎖版本號，同時作為積分事件序號';
