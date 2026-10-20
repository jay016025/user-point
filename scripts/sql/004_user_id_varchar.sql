-- 既有 MySQL 資料庫的三張表仍使用 VARBINARY(64) 時執行一次。
-- 請先停止應用程式並備份資料；既有 user_id 應符合 API 的 ASCII 識別碼規則。
-- utf8mb4_bin 保留大小寫區別；調整外鍵兩側的型別與 collation 後重建外鍵。
ALTER TABLE point_record DROP FOREIGN KEY fk_record_account;

ALTER TABLE points_account
    MODIFY COLUMN user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '使用者識別碼，區分大小寫';

ALTER TABLE point_record
    MODIFY COLUMN user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '積分所屬的使用者識別碼，區分大小寫';

ALTER TABLE points_outbox
    MODIFY COLUMN user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '事件所屬的使用者識別碼，區分大小寫';

ALTER TABLE point_record
    ADD CONSTRAINT fk_record_account FOREIGN KEY (user_id) REFERENCES points_account(user_id);
