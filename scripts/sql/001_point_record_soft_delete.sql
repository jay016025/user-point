-- 既有 MySQL 資料庫升級時執行一次；新建資料庫已由 schema.sql 建立此欄位。
ALTER TABLE point_record
    ADD COLUMN deleted_at TIMESTAMP(6) NULL COMMENT '邏輯刪除時間（UTC），未刪除時為 NULL' AFTER created_at;
