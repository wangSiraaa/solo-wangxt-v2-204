package com.example.craft.domain;

/** 合成单 / 预占 / 流水状态常量。流水只追加，不做更新。 */
public final class LedgerType {
    private LedgerType() {}

    // 合成单状态
    public static final String ORDER_HELD = "HELD";
    public static final String ORDER_COMPLETED = "COMPLETED";
    public static final String ORDER_CANCELLED = "CANCELLED";
    public static final String ORDER_REVOKED = "REVOKED";

    // 预占状态
    public static final String HOLD_HELD = "HELD";
    public static final String HOLD_CONSUMED = "CONSUMED";
    public static final String HOLD_RELEASED = "RELEASED";
    public static final String HOLD_REVERSED = "REVERSED";

    // 流水类型（total_qty / held_qty 的变化方向）
    public static final String L_INIT = "INIT";               // 初始背包
    public static final String L_HOLD = "HOLD";               // 预占：total 不变, held +n
    public static final String L_CONSUME = "CONSUME";         // 消耗：total -n, held -n
    public static final String L_RELEASE = "RELEASE";         // 超时/取消释放：total 不变, held -n
    public static final String L_PRODUCE = "PRODUCE";         // 合成产出：total +n
    public static final String L_REVERSE_PRODUCE = "REVERSE_PRODUCE";   // 撤销-回收产出：total -n
    public static final String L_REVERSE_CONSUME = "REVERSE_CONSUME";   // 撤销-退还材料：total +n

    // 流水关联类型
    public static final String REF_ORDER = "ORDER";
    public static final String REF_EXCEPTION = "EXCEPTION";
    public static final String REF_SEED = "SEED";

    // 取消原因
    public static final String REASON_PLAYER = "PLAYER_CANCEL";
    public static final String REASON_TIMEOUT = "TIMEOUT";

    // 配方版本状态
    public static final String VER_DRAFT = "DRAFT";
    public static final String VER_PUBLISHED = "PUBLISHED";
    public static final String VER_SUPERSEDED = "SUPERSEDED";
}
