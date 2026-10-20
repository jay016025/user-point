-- 既有 MySQL 資料庫升級時執行一次；新建資料庫已由 schema.sql 建立此欄位。
ALTER TABLE points_account
    ADD COLUMN lock_version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 樂觀鎖版本號，用於偵測帳戶並行更新衝突' AFTER version;
