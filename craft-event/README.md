# 限时道具合成活动 · 配方版本 + 玩家账本系统

Vue 3 操作台 + Spring Boot 3 后端 + MySQL/InnoDB 事务。核心目标：**活动结束、背包扣减、请求重试同时发生时，
不多扣一份材料、不重复发一次奖。**

## 目录

```
craft-event/
├── backend/          Spring Boot 3.3 + JdbcTemplate（行锁 + 事务）
│   ├── src/main/resources/schema.sql        建表脚本（InnoDB，CHECK 约束兜底）
│   ├── src/main/java/.../service/CraftService.java   合成/完成/取消/超时/撤销 全部事务逻辑
│   └── src/test/.../CraftConcurrencyIT.java  并发合成等 7 个集成测试（真实 InnoDB）
├── frontend/         Vue 3 + Vite 操作台
└── e2e-smoke.sh      端到端冒烟脚本（curl 驱动）
```

## 1. 快速启动

### 前置

- JDK 17、Maven 3.9+、Node 20+
- MySQL 8.x（开发环境同样兼容 MariaDB 10.11，InnoDB 行锁语义一致）

### 建库

```sql
CREATE DATABASE craft DEFAULT CHARACTER SET utf8mb4;
CREATE USER 'craft'@'%' IDENTIFIED BY 'craft';
GRANT ALL PRIVILEGES ON craft.* TO 'craft'@'%';
FLUSH PRIVILEGES;
```

表结构由后端启动时自动执行 `schema.sql`（`CREATE TABLE IF NOT EXISTS`），演示数据仅在 `account` 表为空时幂等播种。

测试使用独立库（避免与运行实例互相干扰）：

```sql
CREATE DATABASE craft_test DEFAULT CHARACTER SET utf8mb4;
GRANT ALL PRIVILEGES ON craft_test.* TO 'craft'@'%';
```

### 启动后端

```bash
cd backend
# 默认连 127.0.0.1:3306，库 craft，账号/密码 craft/craft
# 可通过环境变量覆盖：DB_HOST DB_PORT DB_USER DB_PASSWORD DB_TZ
mvn spring-boot:run
```

### 启动前端

```bash
cd frontend
npm install
npm run dev          # http://localhost:5173 ，/api 自动代理到 8080
```

### 样例账号（可直接登录）

| 角色 | 用户名 | 密码 | 说明 |
|---|---|---|---|
| 运营 | `ops` | `ops123` | 发布配方、撤销奖励、处理异常清单 |
| 玩家 | `alice` | `alice123` | **宝石 GEM 仅 1 份**，用于并发合成验证 |
| 玩家 | `bob` | `bob123` | 材料充足的普通玩家 |

播种的 4 个配方：

| 配方 | 产出 | 耗时/超时 | 完成方式 | 用途 |
|---|---|---|---|---|
| R001 秘银宝箱 | CHEST×1 | 5s / 15s | 自动完成 | 3 原木+2 铁矿+**1 宝石**（并发关键） |
| R002 星尘福袋 | GIFT×2 | 3s / 10s | 自动完成 | 2 星尘+2 月光草+1 风之羽 |
| R003 炼金实验 | POTION×1 | 2s / 10s | **手动领取** | 不领取则第 10 秒被超时取消并释放占用 |
| R900 往期礼盒 | OLDBOX×1 | — | — | **活动已结束**，服务端必须拒绝新合成 |

## 2. 核心正确性设计（为什么不会多扣 / 重发）

### 2.1 合成：行锁 + 幂等键 + 服务端规则

`CraftService.craft()` 单事务内：

1. `uk_order_idem(account_id, request_id)` 唯一键做请求幂等——重试携带同一 `requestId`，
   先查快路径重放原单；并发同键时唯一键裁决，捕获 `DuplicateKeyException` 后重放先插入的单据。
2. 读取配方**具体版本 id**（不是 recipe code），校验 `PUBLISHED` 与服务器时间活动窗口。
   规则只信服务端，前端按钮是否可点完全不参与判定。
3. 按材料 `seq_no` 顺序对背包行 `SELECT ... FOR UPDATE`——所有请求加锁顺序一致，杜绝交叉死锁；
   同一份材料的两笔合成在 InnoDB 行锁上串行，第二笔看到扣减后的可用量。
4. 逐材料校验可用量（`total_qty - held_qty`）后才写预占行 `material_hold(HELD)` 与
   流水 `HOLD`（total 不变、held+n）。
5. 更新背包的 SQL 带三重非负约束（`total>=0 AND held>=0 AND held<=total`），作为数据库层兜底。

### 2.2 完成 vs 超时取消：单据行锁裁决，状态机只迁一次

- 完成、玩家取消、超时扫描、自动完成调度器，**全部先走 `SELECT ... FROM craft_order WHERE order_no=? FOR UPDATE`**。
- 状态迁移是条件更新：`UPDATE ... SET status='COMPLETED' WHERE order_no=? AND status='HELD'`，
  返回行数必须为 1，否则抛错回滚。任何两条路径并发，恰有一方胜出。
- 完成时：预占 → `CONSUME`（total-n、held-n）+ `PRODUCE`（total+n）；
  取消/超时时：预占 → `RELEASED` + `RELEASE`（held-n，total 不变）。
- 过了 `expire_at` 才点领取：**不会边发奖边释放**，而是直接走超时取消并返回 409。
- 已终态的单据再点领取/取消：接口幂等回显，不产生第二条 `PRODUCE`。

### 2.3 活动结束

- 活动结束后新发起合成：服务端按 `event_ends_at` 拒绝（409），与前端无关。
- 已开始（预占）的合成单外键绑定的是**当时的版本 id 与版本号快照**，配方后续发布新版本、旧版本变
  `SUPERSEDED` 都不影响其完成。

### 2.4 配方版本

- `recipe_version` 是不可变快照（材料在 `recipe_material` 行内）。已 `PUBLISHED` 的版本没有任何更新接口；
  修改只能“基于已发布版本新建 DRAFT → 发布”。
- 发布是单事务：旧 `PUBLISHED → SUPERSEDED` 与新 `DRAFT → PUBLISHED` 条件更新，失败整体回滚。

### 2.5 撤销错误奖励：反向流水 / 异常清单

`CraftService.revoke()`（仅 `COMPLETED` 可撤销，重复撤销 409）：

- 先锁定产出背包行：`total_qty < 应回收数` 表示**奖励（材料）已被玩家使用**——绝不自动扣成负数，
  写入 `revoke_exception`（OPEN），订单维持 `COMPLETED`，不产生任何反向流水；运营可在操作台登记线下处理后关闭。
- 数量足够则在同一事务生成两笔反向流水，`reversal_of` 指向原流水：
  - `REVERSE_PRODUCE`（回产出，total-n）
  - `REVERSE_CONSUME`（逐笔退还当时消耗的材料，total+n）
  - 预占行置 `REVERSED`，单据置 `REVOKED`。

### 2.6 账本（只追加）

`item_ledger` 只插入、不更新不删除，每笔带变动后余额。余额恒等式：

```
Σ qty_change = inventory.total_qty        Σ held_delta = inventory.held_qty
```

流水类型：`INIT / HOLD / CONSUME / RELEASE / PRODUCE / REVERSE_PRODUCE / REVERSE_CONSUME`。
集成测试 `ledger_replay_matches_inventory_after_all_operations` 会回放校验该恒等式。

## 3. 测试

```bash
cd backend
mvn verify                 # 跑 src/test 下的 7 个集成测试（连 craft_test 真实 InnoDB）
```

| 用例 | 覆盖点 |
|---|---|
| `last_single_material_concurrent_craft_only_one_wins` | 8 线程同时抢唯一 1 份材料：恰好 1 单 200、7 单 409；流水/预占均只有 1 笔 |
| `retried_same_request_id_is_idempotent` | 同 `requestId` 连发 3 次：同单号、单据 1 条、不重复预占 |
| `complete_and_timeout_race_exactly_one_outcome` | 领取与超时扫描并发：只发一次奖，不出现既发奖又释放 |
| `expired_hold_is_released_and_cannot_be_completed` | 超时释放后材料归还、占用归零、无产出；扫描重入幂等 |
| `event_ended_blocks_new_craft_...` | 活动结束后拒绝新单，已开始的单仍按快照版本完成 |
| `revoke_produces_reversal_ledger_or_exception_when_consumed` | 奖励未用→反向流水+退材料；已用→异常清单；重复撤销 409 |
| `ledger_replay_matches_inventory_after_all_operations` | 取消/完成/撤销后流水回放 == 背包余额 |

另有端到端脚本（需先启动后端与数据库）：

```bash
./e2e-smoke.sh
```

前端玩家台「合成预览」面板有 **并发自检：同时发起 6 单** 按钮，浏览器内即可观察
“恰好一单成功、其余服务端 409”，以及每笔请求各自的 `requestId`。

## 4. 主要 HTTP 接口

令牌放在 `Authorization: Bearer <token>`。POST 写接口建议带 `requestId`（前端自动生成，
502/503/网络错误时用同一个键自动重试）。

- `POST /api/auth/login` `{username,password}`
- 玩家：`GET /api/player/recipes`、`GET /api/player/recipes/{versionId}/preview`、
  `POST /api/player/craft` `{versionId,qty,requestId}`、
  `POST /api/player/orders/{orderNo}/complete|cancel`、
  `GET /api/player/orders|orders/{orderNo}|inventory|ledger|holds`
- 运营：`GET /api/operator/recipes?allVersions=true`、`POST /api/operator/recipes/drafts`、
  `POST /api/operator/recipes/versions/{id}/publish`、`GET /api/operator/orders`、
  `POST /api/operator/orders/{orderNo}/revoke`、`GET /api/operator/exceptions`、
  `POST /api/operator/exceptions/{no}/resolve`

错误码：401 未登录 / 403 越权（玩家调运营接口等）/ 409 业务冲突（材料不足、活动结束、状态不符、重复请求）。

## 5. 时区说明

应用墙钟、JDBC 时间参数与数据库 `NOW()` 必须在同一时区基准上（默认均为容器/服务器时区，
生产建议统一 UTC）。如 JVM 与 DB 不同时区，用 `DB_TZ` 覆盖 JDBC `serverTimezone`，
并保证数据库装载了对应时区表（使用数字偏移如 `%2B08%3A00` 可避免命名时区表缺失问题）。
