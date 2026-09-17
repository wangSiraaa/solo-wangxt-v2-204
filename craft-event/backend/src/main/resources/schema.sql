-- 限时道具合成：配方版本 / 合成单 / 预占 / 逐笔流水
-- 目标库：MySQL 8.x（开发环境同样兼容 MariaDB 10.11，均为 InnoDB 行锁语义）

CREATE TABLE IF NOT EXISTS account (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    username        VARCHAR(64)  NOT NULL,
    password_hash   VARCHAR(100) NOT NULL,
    display_name    VARCHAR(64)  NOT NULL,
    role            VARCHAR(16)  NOT NULL COMMENT 'PLAYER / OPERATOR',
    enabled         TINYINT(1)   NOT NULL DEFAULT 1,
    created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '登录账号（运营/玩家）';

CREATE TABLE IF NOT EXISTS login_token (
    token       CHAR(36)    NOT NULL,
    account_id  BIGINT      NOT NULL,
    created_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    expires_at  DATETIME(3) NOT NULL,
    PRIMARY KEY (token),
    KEY idx_token_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '登录令牌（重试安全：登出只删当次令牌）';

CREATE TABLE IF NOT EXISTS item (
    id      BIGINT      NOT NULL AUTO_INCREMENT,
    code    VARCHAR(32) NOT NULL,
    name    VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_item_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '道具字典';

CREATE TABLE IF NOT EXISTS inventory (
    id          BIGINT    NOT NULL AUTO_INCREMENT,
    account_id  BIGINT    NOT NULL,
    item_id     BIGINT    NOT NULL,
    total_qty   INT       NOT NULL DEFAULT 0 COMMENT '可用+占用的总数量',
    held_qty    INT       NOT NULL DEFAULT 0 COMMENT '已预占（合成中）数量',
    updated_at  DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_inventory_account_item (account_id, item_id),
    CONSTRAINT chk_inventory_qty CHECK (total_qty >= 0 AND held_qty >= 0 AND held_qty <= total_qty)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '玩家背包（余额表，权威以流水回放为准）';

CREATE TABLE IF NOT EXISTS recipe (
    id      BIGINT      NOT NULL AUTO_INCREMENT,
    code    VARCHAR(32) NOT NULL,
    name    VARCHAR(64) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recipe_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '配方逻辑实体（发布后修改=新建版本）';

CREATE TABLE IF NOT EXISTS recipe_version (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    recipe_id        BIGINT       NOT NULL,
    version_no       INT          NOT NULL,
    output_item_id   BIGINT       NOT NULL,
    output_qty       INT          NOT NULL,
    event_starts_at  DATETIME(3)  NOT NULL,
    event_ends_at    DATETIME(3)  NOT NULL,
    auto_complete    TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '到期自动完成；0=需玩家手动领取（用于演示超时）',
    craft_seconds    INT          NOT NULL COMMENT '合成耗时（预占后最早可领取/自动完成时刻）',
    timeout_seconds  INT          NOT NULL COMMENT '预占超时秒数；超过仍未完成则取消并释放占用',
    status           VARCHAR(16)  NOT NULL COMMENT 'DRAFT/PUBLISHED/SUPERSEDED',
    changelog        VARCHAR(255) NULL,
    created_by       VARCHAR(64)  NOT NULL,
    created_at       DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at     DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_recipe_version_no (recipe_id, version_no),
    KEY rv_published_idx (status, event_ends_at),
    CONSTRAINT chk_version_window CHECK (event_ends_at > event_starts_at),
    CONSTRAINT chk_version_output CHECK (output_qty > 0 AND craft_seconds > 0 AND timeout_seconds > craft_seconds)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '配方版本（不可变快照）';

CREATE TABLE IF NOT EXISTS recipe_material (
    id                 BIGINT   NOT NULL AUTO_INCREMENT,
    recipe_version_id  BIGINT   NOT NULL,
    item_id            BIGINT   NOT NULL,
    qty                INT      NOT NULL,
    seq_no             INT      NOT NULL COMMENT '加锁顺序，防止多材料交叉死锁',
    PRIMARY KEY (id),
    UNIQUE KEY uk_rm_version_item (recipe_version_id, item_id),
    CONSTRAINT chk_rm_qty CHECK (qty > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '配方材料行（版本快照的一部分）';

CREATE TABLE IF NOT EXISTS craft_order (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    order_no           VARCHAR(40)  NOT NULL,
    account_id         BIGINT       NOT NULL,
    recipe_id          BIGINT       NOT NULL,
    recipe_version_id  BIGINT       NOT NULL,
    snapshot_version   INT          NOT NULL COMMENT '预占时的配方版本号，完成时不变',
    output_item_id     BIGINT       NOT NULL,
    output_qty         INT          NOT NULL,
    qty                INT          NOT NULL COMMENT '合成份数',
    status             VARCHAR(16)  NOT NULL COMMENT 'HELD/COMPLETED/CANCELLED/REVOKED',
    auto_complete      TINYINT(1)   NOT NULL,
    held_from          DATETIME(3)  NOT NULL COMMENT '预占起点（合成开始）',
    complete_at        DATETIME(3)  NOT NULL COMMENT '最早可完成（领取）时刻',
    expire_at          DATETIME(3)  NOT NULL COMMENT '预占截止；仍未完成则超时释放',
    completed_at       DATETIME(3)  NULL,
    cancelled_at       DATETIME(3)  NULL,
    cancel_reason      VARCHAR(32)  NULL COMMENT 'PLAYER_CANCEL / TIMEOUT',
    revoked_at         DATETIME(3)  NULL,
    revoke_operator    VARCHAR(64)  NULL,
    request_id         VARCHAR(64)  NOT NULL COMMENT '客户端幂等键（请求重试去重）',
    created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_no (order_no),
    UNIQUE KEY uk_order_idem (account_id, request_id),
    KEY idx_order_account (account_id, status),
    KEY idx_order_status_time (status, expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '合成单（状态机：HELD→COMPLETED/CANCELLED，COMPLETED→REVOKED）';

CREATE TABLE IF NOT EXISTS material_hold (
    id           BIGINT      NOT NULL AUTO_INCREMENT,
    order_no     VARCHAR(40) NOT NULL,
    account_id   BIGINT      NOT NULL,
    item_id      BIGINT      NOT NULL,
    qty          INT         NOT NULL,
    status       VARCHAR(16) NOT NULL COMMENT 'HELD/CONSUMED/RELEASED/REVERSED',
    created_at   DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    consumed_at  DATETIME(3) NULL,
    released_at  DATETIME(3) NULL,
    reversed_at  DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_hold_order (order_no),
    KEY idx_hold_account_item (account_id, item_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '逐笔材料预占去向';

CREATE TABLE IF NOT EXISTS item_ledger (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    account_id    BIGINT       NOT NULL,
    item_id       BIGINT       NOT NULL,
    change_type   VARCHAR(24)  NOT NULL COMMENT 'INIT/HOLD/CONSUME/RELEASE/PRODUCE/REVERSE_PRODUCE/REVERSE_CONSUME',
    qty_change    INT          NOT NULL COMMENT 'total_qty 带符号变化',
    held_delta    INT          NOT NULL COMMENT 'held_qty 带符号变化',
    balance_total INT          NOT NULL COMMENT '本笔后 total_qty',
    balance_held  INT          NOT NULL COMMENT '本笔后 held_qty',
    ref_type      VARCHAR(24)  NOT NULL COMMENT 'ORDER / EXCEPTION / SEED',
    ref_no        VARCHAR(40)  NOT NULL,
    reversal_of   BIGINT       NULL COMMENT '撤销时指向被冲正的原流水',
    remark        VARCHAR(255) NULL,
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_ledger_account_item_time (account_id, item_id, id),
    KEY idx_ledger_ref (ref_type, ref_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '材料/产出逐笔流水（只追加，不修改不删除）';

CREATE TABLE IF NOT EXISTS revoke_exception (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    exception_no    VARCHAR(40)  NOT NULL,
    order_no        VARCHAR(40)  NOT NULL,
    account_id      BIGINT       NOT NULL,
    reason          VARCHAR(255) NOT NULL COMMENT '产出材料已被使用/不足，无法回收',
    missing_item_id BIGINT       NULL,
    missing_qty     INT          NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'OPEN' COMMENT 'OPEN/RESOLVED',
    remark          VARCHAR(255) NULL,
    created_by      VARCHAR(64)  NOT NULL,
    created_at      DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    resolved_at     DATETIME(3) NULL,
    resolved_by     VARCHAR(64)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_exception_no (exception_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT '撤销异常清单（材料已使用，无法反向）';
