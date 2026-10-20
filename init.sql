CREATE TABLE IF NOT EXISTS points_account (
    user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin PRIMARY KEY COMMENT '使用者識別碼，區分大小寫',
    total BIGINT NOT NULL DEFAULT 0 COMMENT '使用者目前積分總額',
    version BIGINT NOT NULL DEFAULT 0 COMMENT 'JPA 樂觀鎖版本號，同時作為積分事件序號',
    CONSTRAINT ck_points_total CHECK (total >= 0 AND total <= 9007199254740991)
);

CREATE TABLE IF NOT EXISTS point_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT '積分紀錄編號，自動遞增',
    user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '積分所屬的使用者識別碼，區分大小寫',
    amount BIGINT NOT NULL COMMENT '本筆紀錄增加的積分數',
    reason VARCHAR(255) NOT NULL COMMENT '增加積分的原因',
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) COMMENT '積分紀錄建立時間（UTC）',
    deleted_at TIMESTAMP(6) NULL COMMENT '邏輯刪除時間（UTC），未刪除時為 NULL',
    CONSTRAINT fk_record_account FOREIGN KEY (user_id) REFERENCES points_account(user_id),
    CONSTRAINT ck_positive_amount CHECK (amount > 0),
    INDEX idx_record_user (user_id)
);

CREATE TABLE IF NOT EXISTS points_outbox (
    event_id VARCHAR(36) PRIMARY KEY COMMENT '積分異動事件的唯一識別碼，供訊息去重使用',
    event_type VARCHAR(32) NOT NULL COMMENT '事件類型：新事件僅為 POINTS_ADDED；POINTS_DELETED 僅供相容歷史資料',
    user_id VARCHAR(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL COMMENT '事件所屬的使用者識別碼，區分大小寫',
    record_id BIGINT NOT NULL COMMENT '關聯的積分紀錄編號，歷史刪除事件為 0',
    amount BIGINT NOT NULL COMMENT '本次增加的積分數，歷史刪除事件為 0',
    total BIGINT NOT NULL COMMENT '事件發生後的使用者積分總額',
    version BIGINT NOT NULL COMMENT '使用者積分事件版本號，用於避免舊事件覆蓋新狀態',
    reason VARCHAR(255) NOT NULL COMMENT '積分異動原因，歷史刪除事件為空字串',
    occurred_at TIMESTAMP(6) NOT NULL COMMENT '積分異動事件發生時間（UTC）',
    delivered BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否已完成事件處理：排行榜更新、快取失效與加分 MQ 投遞',
    INDEX idx_outbox_pending (delivered, occurred_at)
);

-- 測試資料：10 位使用者各有一筆加分紀錄，帳戶總積分與紀錄金額一致。
START TRANSACTION;

INSERT INTO points_account (user_id, total, version) VALUES
    ('user_001', 1000, 1),
    ('user_002', 900, 1),
    ('user_003', 800, 1),
    ('user_004', 700, 1),
    ('user_005', 600, 1),
    ('user_006', 500, 1),
    ('user_007', 400, 1),
    ('user_008', 300, 1),
    ('user_009', 200, 1),
    ('user_010', 100, 1);

INSERT INTO point_record (user_id, amount, reason) VALUES
    ('user_001', 1000, 'test_seed'),
    ('user_002', 900, 'test_seed'),
    ('user_003', 800, 'test_seed'),
    ('user_004', 700, 'test_seed'),
    ('user_005', 600, 'test_seed'),
    ('user_006', 500, 'test_seed'),
    ('user_007', 400, 'test_seed'),
    ('user_008', 300, 'test_seed'),
    ('user_009', 200, 'test_seed'),
    ('user_010', 100, 'test_seed');

COMMIT;
