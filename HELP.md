# User Points Service

## 啟動專案

需要 Java 21（`JAVA_HOME` 指向 JDK 21）及 Docker Compose。以下設定適用於 Java 在本機、基礎服務在 Docker 執行。

團隊共用的本機開發帳密放在 `.env.development`（納入 Git）；個人覆寫可放 `.env.local`（不納入 Git）。應用程式設定在 `src/main/resources/application.yaml`，透過環境變數載入：

| 環境變數 | 設定 |
| --- | --- |
| `DB_URL` | `jdbc:mysql://localhost:3306/taskdb?useUnicode=true&characterEncoding=UTF-8&connectionTimeZone=UTC` |
| `DB_USERNAME` / `DB_PASSWORD` | 由 `.env.development` 載入 |
| `MYSQL_ROOT_PASSWORD` | 由 `.env.development` 載入，供 Docker MySQL 使用 |
| `REDIS_HOST` / `REDIS_PORT` | `localhost` / `6379` |
| `ROCKETMQ_NAMESERVER` | `localhost:9876` |
| `ROCKETMQ_PRODUCER_GROUP` | `user-points-producer` |

```sh
docker compose --env-file .env.development up -d mysql redis rocketmq-namesrv rocketmq-broker
docker compose --env-file .env.development ps
```

確認 MySQL、Redis 為 healthy 後啟動應用程式，API 位址為 `http://localhost:8080`：

```sh
set -a
. ./.env.development
# 若有個人設定，接著執行：. ./.env.local
set +a
./mvnw spring-boot:run
```

IDE 啟動時也需載入相同環境變數。Compose 若要套用個人設定，在指令中加上第二個 `--env-file .env.local`。

`broker.conf` 的 `brokerIP1=127.0.0.1` 供本機 Java 連線；若 Java 改在其他容器或主機執行，需調整為可連線的 broker 位址。

資料表由 `src/main/resources/schema.sql` 自動建立。既有資料庫不會自動修改欄位：缺少 `deleted_at` 時執行 [001](scripts/sql/001_point_record_soft_delete.sql)，仍有 `lock_version` 時執行 [003](scripts/sql/003_merge_account_versions.sql)；新資料庫不需執行升級腳本。

三張表的 `user_id` 使用 `VARCHAR(64)`、`utf8mb4_bin` 區分大小寫，Java 對應 `String`。舊庫仍為 `VARBINARY` 時，停止應用程式並備份後執行 [004](scripts/sql/004_user_id_varchar.sql)。

選用：在尚無測試資料的資料庫匯入 `init.sql`，建立 10 位使用者的積分資料，只需執行一次：

```sh
docker compose --env-file .env.development exec -T mysql sh -c 'exec mysql --default-character-set=utf8mb4 -uroot -p"$MYSQL_ROOT_PASSWORD" taskdb' < init.sql
```

驗證 MQ 訊息：先啟動 Docker 服務，在已載入上述環境變數的終端執行以下 consumer 整合測試。測試會呼叫加分 API，從 broker 收到對應事件後印出訊息 ID 與 JSON，並清理自己的 DB／Redis 測試資料。

```sh
./mvnw -Dtest=RocketMqConsumerIT test
```

## 重點架構設計

採用 Controller → Service → Persistence Adapter → JPA／Redis 分層，透過 MapStruct 轉換各層模型。

### 樂觀鎖

- 帳戶只保留一個 `version`，同時作為 JPA `@Version` 與事件版本。
- 更新時比對讀取的版本，衝突就回滾整筆交易，避免並行加分互相覆蓋；flush 後取得新版本建立事件。
- 樂觀鎖衝突及建立帳戶時的 `DataIntegrityViolationException` 會重試，最多 **3 次，包含首次**，每次使用新交易。耗盡後回傳「加入積分失敗，請稍後重試」。

### Outbox Pattern

- 加分的帳戶、積分紀錄與 outbox 事件在同一個 MySQL 交易提交，避免資料成功寫入卻遺失待發訊息。
- `publishEvent()` 發布 Spring 內部事件；`@TransactionalEventListener(AFTER_COMMIT)` 在提交後觸發派送。
- Worker 鎖定尚未完成的 outbox，更新排行榜、清除快取，再發送 RocketMQ；成功後標記 `delivered=true`。
- 排程每秒補送未完成事件，每批最多 50 筆；即時派送與排程共用鎖定及狀態檢查，已完成就跳過。
- MQ 與 MySQL 不共用交易，發送成功但狀態尚未提交時中斷，仍可能重送，因此 consumer 必須以 `eventId` 做冪等處理。

### 為什麼使用 Lua

單筆排行榜更新需要連續完成「讀取版本 → 比較版本 → 更新版本與分數」。Lua 讓這些操作在 Redis 內原子執行，避免並行更新交錯，以及重複或過期事件覆蓋新分數。

零分使用者移出排行榜時仍保留版本，阻擋舊加分事件讓使用者重新上榜。版本補零為 19 位後使用字串比較，避免 Lua 浮點數遺失 BIGINT 精度。

### 快取、排行榜與刪除

- 總積分透過 `@Cacheable` 查 Redis，未命中再讀 MySQL，TTL 為 60 秒；加分、刪除成功後以 `@CacheEvict` 清除快取。
- Top 10 先查 Redis，空榜時回查 MySQL，回傳正分前 10 名，並批次回填全部帳戶的分數與版本，保留後續遞補排名。
- **回填不使用 Lua**，直接以 Redis 批次指令寫入；不做原子版本比較，回填期間的並行更新可能被 DB 快照覆蓋。
- 刪除只處理既有帳戶：積分歸零、明細設定 `deleted_at`，提交後清除排行榜與快取；不存在的使用者直接成功，不建立帳戶，也不產生刪除事件或 MQ 通知。
- 刪除若在 Redis 清理階段失敗，MySQL 已提交，可重試 DELETE 完成清理。
