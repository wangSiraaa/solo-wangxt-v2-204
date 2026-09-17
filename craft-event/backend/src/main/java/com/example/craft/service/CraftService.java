package com.example.craft.service;

import com.example.craft.domain.CraftOrder;
import com.example.craft.domain.Inventory;
import com.example.craft.domain.LedgerEntry;
import com.example.craft.domain.LedgerType;
import com.example.craft.domain.MaterialHold;
import com.example.craft.domain.RecipeMaterial;
import com.example.craft.domain.RecipeVersion;
import com.example.craft.domain.RevokeException;
import com.example.craft.repo.ExceptionRepo;
import com.example.craft.repo.HoldRepo;
import com.example.craft.repo.InventoryRepo;
import com.example.craft.repo.ItemRepo;
import com.example.craft.repo.LedgerRepo;
import com.example.craft.repo.OrderRepo;
import com.example.craft.repo.RecipeRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 合成生命周期（全部以数据库事务 + 行锁为准，按钮是否可点不参与判定）：
 *
 *  提交 craft： uk_order_idem 幂等键 → 锁配方发布版本 → 活动窗口/时间校验
 *              → 按 seq_no 顺序对材料背包行 SELECT ... FOR UPDATE
 *              → 可用量校验（服务端）→ 预占行(HOLD) + HELD 流水 + 单据(HELD)
 *  完成 complete：锁单据 → 状态/时间校验 → 预占行 CONSUMED + 消耗流水 + 产出流水 + 单据 COMPLETED
 *  取消 cancel：锁单据 → RELEASED 预占 + RELEASE 流水 + 单据 CANCELLED
 *  超时 sweep：与取消完全相同的事务路径，只是 reason=TIMEOUT
 *  撤销 revoke：COMPLETED 单据 → 先检查产出是否仍在背包 → 不足写异常清单(不自动扣负)
 *              → 足够则反向流水：REVERSE_PRODUCE（回产出）+ REVERSE_CONSUME（还材料）
 */
@Service
public class CraftService {

    private static final Logger log = LoggerFactory.getLogger(CraftService.class);

    private final OrderRepo orderRepo;
    private final HoldRepo holdRepo;
    private final InventoryRepo inventoryRepo;
    private final LedgerRepo ledgerRepo;
    private final RecipeRepo recipeRepo;
    private final ItemRepo itemRepo;
    private final ExceptionRepo exceptionRepo;

    public CraftService(OrderRepo orderRepo, HoldRepo holdRepo, InventoryRepo inventoryRepo,
                        LedgerRepo ledgerRepo, RecipeRepo recipeRepo, ItemRepo itemRepo,
                        ExceptionRepo exceptionRepo) {
        this.orderRepo = orderRepo;
        this.holdRepo = holdRepo;
        this.inventoryRepo = inventoryRepo;
        this.ledgerRepo = ledgerRepo;
        this.recipeRepo = recipeRepo;
        this.itemRepo = itemRepo;
        this.exceptionRepo = exceptionRepo;
    }

    // ------------------------------ 提交合成（预占） ------------------------------

    /**
     * 幂等：同一玩家同一 requestId 的重试只返回原单。
     * 并发：不同 requestId 抢同一份材料时，材料背包行锁串行裁决，恰有一方得到 409。
     */
    @Transactional
    public CraftOrder craft(Long accountId, long versionId, int qty, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            throw ApiException.badRequest("requestId 必填（请求重试的幂等键）");
        }
        if (qty <= 0 || qty > 99) {
            throw ApiException.badRequest("合成份数必须在 1~99 之间");
        }

        // 快路径：重放已有单据（请求重试 / 网络抖动后用户再点）
        CraftOrder existing = orderRepo.findByIdempotency(accountId, requestId).orElse(null);
        if (existing != null) {
            return existing;
        }

        RecipeVersion version = recipeRepo.findVersionById(versionId);
        if (version == null || !LedgerType.VER_PUBLISHED.equals(version.status())) {
            throw ApiException.conflict("该配方版本不存在或未发布（可能已有新版本，请刷新）");
        }
        List<RecipeMaterial> materials = recipeRepo.findMaterials(version.id());
        if (materials.isEmpty()) {
            throw ApiException.conflict("配方版本缺少材料定义");
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        // 规则由服务端判定：活动是否开放以服务器时间为准
        if (now.isBefore(version.eventStartsAt())) {
            throw ApiException.conflict("活动尚未开始：" + version.eventStartsAt());
        }
        if (!now.isBefore(version.eventEndsAt())) {
            throw ApiException.conflict("活动已结束，不能再发起合成（已开始的合成不受影响）");
        }
        // 活动结束前来不及领取的预占没有意义，但仍允许提交——超时任务会负责释放
        LocalDateTime heldFrom = now;
        LocalDateTime completeAt = heldFrom.plusSeconds(version.craftSeconds());
        LocalDateTime expireAt = heldFrom.plusSeconds(version.timeoutSeconds());

        // 按 seq_no（= item 排序）锁行，所有请求顺序一致，杜绝交叉死锁
        for (RecipeMaterial m : materials) {
            Inventory inv = inventoryRepo.lockForUpdate(accountId, m.itemId());
            int need = m.qty() * qty;
            if (inv.available() < need) {
                throw ApiException.conflict("材料不足：" + itemName(m.itemId())
                        + " 需要 " + need + "，可用 " + inv.available());
            }
        }

        String orderNo = newOrderNo();
        // 材料检查全部通过后才写预占与预占流水
        for (RecipeMaterial m : materials) {
            int need = m.qty() * qty;
            Inventory inv = inventoryRepo.lockForUpdate(accountId, m.itemId());
            int newHeld = inv.heldQty() + need;
            inventoryRepo.applyDelta(accountId, m.itemId(), 0, need);
            ledgerRepo.append(accountId, m.itemId(), LedgerType.L_HOLD, 0, need,
                    inv.totalQty(), newHeld, LedgerType.REF_ORDER, orderNo, null,
                    "合成预占 v" + version.versionNo());
            holdRepo.insert(orderNo, accountId, m.itemId(), need);
        }

        CraftOrder order = new CraftOrder(
                null, orderNo, accountId, version.recipeId(), version.id(), version.versionNo(),
                version.outputItemId(), version.outputQty(), qty, LedgerType.ORDER_HELD,
                version.autoComplete(), heldFrom, completeAt, expireAt,
                null, null, null, null, null, requestId, now);
        try {
            orderRepo.insert(order);
        } catch (org.springframework.dao.DuplicateKeyException dup) {
            // 两个并发请求使用了相同 requestId：唯一键裁决，重放先插入的那一单
            CraftOrder winner = orderRepo.findByIdempotency(accountId, requestId)
                    .orElseThrow(() -> dup);
            log.info("duplicate requestId={} replayed to order={}", requestId, winner.orderNo());
            return winner;
        }
        log.info("craft held order={} account={} recipeVersion={} qty={}",
                orderNo, accountId, versionId, qty);
        return order;
    }

    // ------------------------------ 完成合成（领取产出） ------------------------------

    @Transactional
    public CraftOrder complete(Long accountId, String orderNo) {
        CraftOrder order = orderRepo.lockByOrderNo(orderNo);
        if (order == null || !order.accountId().equals(accountId)) {
            throw ApiException.notFound("合成单不存在");
        }
        if (!LedgerType.ORDER_HELD.equals(order.status())) {
            // 已完成/已取消/已撤销 → 对调用方幂等，直接回显，绝不重复发奖
            return order;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        if (now.isBefore(order.completeAt())) {
            long wait = ChronoUnit.SECONDS.between(now, order.completeAt());
            throw ApiException.conflict("合成进行中，还需等待约 " + Math.max(1, wait) + " 秒");
        }
        if (!now.isBefore(order.expireAt())) {
            // 已过超时点：领取失败，立刻走超时释放，而不是边发奖边释放
            doCancelLocked(order, now, LedgerType.REASON_TIMEOUT);
            throw ApiException.conflict("预占已超时，材料已释放，合成单取消");
        }
        doCompleteLocked(order, now);
        return orderRepo.lockByOrderNo(orderNo);
    }

    private void doCompleteLocked(CraftOrder order, LocalDateTime now) {
        List<MaterialHold> holds = holdRepo.findByOrderNoForUpdate(order.orderNo());
        for (MaterialHold h : holds) {
            if (!LedgerType.HOLD_HELD.equals(h.status())) {
                throw new IllegalStateException("hold not HELD: " + h.id() + " status=" + h.status());
            }
            Inventory inv = inventoryRepo.lockForUpdate(order.accountId(), h.itemId());
            int newTotal = inv.totalQty() - h.qty();
            int newHeld = inv.heldQty() - h.qty();
            inventoryRepo.applyDelta(order.accountId(), h.itemId(), -h.qty(), -h.qty());
            ledgerRepo.append(order.accountId(), h.itemId(), LedgerType.L_CONSUME, -h.qty(), -h.qty(),
                    newTotal, newHeld, LedgerType.REF_ORDER, order.orderNo(), null, "合成消耗");
            if (holdRepo.consume(h.id(), now) != 1) {
                throw new IllegalStateException("consume hold lost race: " + h.id());
            }
        }
        // 产出：确保背包行存在（玩家可能第一次获得该道具）
        inventoryRepo.insertIfAbsent(order.accountId(), order.outputItemId(), 0);
        Inventory outInv = inventoryRepo.lockForUpdate(order.accountId(), order.outputItemId());
        int produced = order.outputQty() * order.qty();
        inventoryRepo.applyDelta(order.accountId(), order.outputItemId(), produced, 0);
        ledgerRepo.append(order.accountId(), order.outputItemId(), LedgerType.L_PRODUCE, produced, 0,
                outInv.totalQty() + produced, outInv.heldQty(),
                LedgerType.REF_ORDER, order.orderNo(), null, "合成产出");
        if (orderRepo.markCompleted(order.orderNo(), now) != 1) {
            throw new IllegalStateException("complete order lost race: " + order.orderNo());
        }
        log.info("craft completed order={}", order.orderNo());
    }

    // ------------------------------ 玩家取消 / 超时释放 ------------------------------

    @Transactional
    public CraftOrder cancel(Long accountId, String orderNo) {
        CraftOrder order = orderRepo.lockByOrderNo(orderNo);
        if (order == null || !order.accountId().equals(accountId)) {
            throw ApiException.notFound("合成单不存在");
        }
        if (!LedgerType.ORDER_HELD.equals(order.status())) {
            return order; // 幂等回显
        }
        doCancelLocked(order, LocalDateTime.now().withNano(0), LedgerType.REASON_PLAYER);
        return orderRepo.lockByOrderNo(orderNo);
    }

    private void doCancelLocked(CraftOrder order, LocalDateTime now, String reason) {
        List<MaterialHold> holds = holdRepo.findByOrderNoForUpdate(order.orderNo());
        for (MaterialHold h : holds) {
            if (!LedgerType.HOLD_HELD.equals(h.status())) {
                continue; // 防御：只有 HELD 才释放
            }
            Inventory inv = inventoryRepo.lockForUpdate(order.accountId(), h.itemId());
            int newHeld = inv.heldQty() - h.qty();
            inventoryRepo.applyDelta(order.accountId(), h.itemId(), 0, -h.qty());
            ledgerRepo.append(order.accountId(), h.itemId(), LedgerType.L_RELEASE, 0, -h.qty(),
                    inv.totalQty(), newHeld, LedgerType.REF_ORDER, order.orderNo(), null,
                    LedgerType.REASON_TIMEOUT.equals(reason) ? "超时取消，释放占用" : "玩家取消，释放占用");
            if (holdRepo.release(h.id(), now) != 1) {
                throw new IllegalStateException("release hold lost race: " + h.id());
            }
        }
        if (orderRepo.markCancelled(order.orderNo(), now, reason) != 1) {
            throw new IllegalStateException("cancel order lost race: " + order.orderNo());
        }
        log.info("craft cancelled order={} reason={}", order.orderNo(), reason);
    }

    /** 定时任务/测试使用：处理一单自动完成，返回是否进行了状态迁移。 */
    @Transactional
    public boolean sweepAutoComplete(String orderNo, LocalDateTime now) {
        CraftOrder order = orderRepo.lockByOrderNo(orderNo);
        if (order == null || !LedgerType.ORDER_HELD.equals(order.status()) || !order.autoComplete()) {
            return false;
        }
        if (now.isBefore(order.completeAt()) || !now.isBefore(order.expireAt())) {
            return false;
        }
        doCompleteLocked(order, now);
        return true;
    }

    /** 定时任务/测试使用：处理一单超时，返回是否进行了释放。 */
    @Transactional
    public boolean sweepTimeout(String orderNo, LocalDateTime now) {
        CraftOrder order = orderRepo.lockByOrderNo(orderNo);
        if (order == null || !LedgerType.ORDER_HELD.equals(order.status())) {
            return false;
        }
        if (now.isBefore(order.expireAt())) {
            return false;
        }
        doCancelLocked(order, now, LedgerType.REASON_TIMEOUT);
        return true;
    }

    // ------------------------------ 运营撤销（反向流水 / 异常清单） ------------------------------

    @Transactional
    public Map<String, Object> revoke(String orderNo, String operator) {
        CraftOrder order = orderRepo.lockByOrderNo(orderNo);
        if (order == null) {
            throw ApiException.notFound("合成单不存在: " + orderNo);
        }
        if (LedgerType.ORDER_REVOKED.equals(order.status())) {
            throw ApiException.conflict("该单已撤销，请勿重复操作");
        }
        if (!LedgerType.ORDER_COMPLETED.equals(order.status())) {
            throw ApiException.conflict("仅已完成（COMPLETED）的合成可撤销，当前状态: " + order.status());
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        int produced = order.outputQty() * order.qty();
        Inventory outInv = inventoryRepo.lockForUpdate(order.accountId(), order.outputItemId());

        Map<String, Object> result = new LinkedHashMap<>();
        // 材料（产出物）已被使用：可用 + 占用都不足以回收 → 不自动扣负，进异常清单
        if (outInv.totalQty() < produced) {
            String exNo = "E" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(1000, 9999);
            String reason = "产出材料已被使用：应回收 " + itemName(order.outputItemId()) + " x" + produced
                    + "，背包仅剩 " + outInv.totalQty() + "，无法生成反向流水";
            exceptionRepo.insert(exNo, orderNo, order.accountId(), reason,
                    order.outputItemId(), produced - outInv.totalQty(), operator);
            result.put("reversed", false);
            result.put("exceptionNo", exNo);
            result.put("reason", reason);
            log.warn("revoke blocked order={} -> exception={}", orderNo, exNo);
            return result;
        }

        // 反向流水 1：回收产出（REVERSE_PRODUCE 指向原 PRODUCE）
        LedgerEntry produce = ledgerRepo.findProduceEntry(orderNo, order.accountId(), order.outputItemId());
        int newOutTotal = outInv.totalQty() - produced;
        inventoryRepo.applyDelta(order.accountId(), order.outputItemId(), -produced, 0);
        ledgerRepo.append(order.accountId(), order.outputItemId(), LedgerType.L_REVERSE_PRODUCE,
                -produced, 0, newOutTotal, outInv.heldQty(),
                LedgerType.REF_ORDER, orderNo, produce == null ? null : produce.id(),
                "运营撤销：回收错误奖励");

        // 反向流水 2：逐笔退还当时消耗的材料（REVERSE_CONSUME 指向原 CONSUME）
        for (MaterialHold h : holdRepo.findByOrderNoForUpdate(orderNo)) {
            if (!LedgerType.HOLD_CONSUMED.equals(h.status())) {
                continue;
            }
            List<LedgerEntry> refs = ledgerRepo.findByRef(LedgerType.REF_ORDER, orderNo).stream()
                    .filter(e -> e.itemId().equals(h.itemId())
                            && LedgerType.L_CONSUME.equals(e.changeType()))
                    .toList();
            Long consumeLedgerId = refs.isEmpty() ? null : refs.get(0).id();

            inventoryRepo.insertIfAbsent(order.accountId(), h.itemId(), 0);
            Inventory mInv = inventoryRepo.lockForUpdate(order.accountId(), h.itemId());
            int newTotal = mInv.totalQty() + h.qty();
            inventoryRepo.applyDelta(order.accountId(), h.itemId(), h.qty(), 0);
            ledgerRepo.append(order.accountId(), h.itemId(), LedgerType.L_REVERSE_CONSUME,
                    h.qty(), 0, newTotal, mInv.heldQty(),
                    LedgerType.REF_ORDER, orderNo, consumeLedgerId,
                    "运营撤销：退还合成材料");
            if (holdRepo.reverse(h.id(), now) != 1) {
                throw new IllegalStateException("reverse hold lost race: " + h.id());
            }
        }

        if (orderRepo.markRevoked(orderNo, now, operator) != 1) {
            throw new IllegalStateException("revoke order lost race: " + orderNo);
        }
        result.put("reversed", true);
        result.put("orderNo", orderNo);
        log.info("craft revoked order={} by={}", orderNo, operator);
        return result;
    }

    @Transactional
    public void resolveException(String exceptionNo, String operator, String remark) {
        int rows = exceptionRepo.resolve(exceptionNo, operator,
                remark == null || remark.isBlank() ? "线下处理完成" : remark, LocalDateTime.now().withNano(0));
        if (rows != 1) {
            throw ApiException.conflict("异常单不存在或已处理: " + exceptionNo);
        }
    }

    // ------------------------------ 查询视图 ------------------------------

    @Transactional(readOnly = true)
    public Map<String, Object> preview(long versionId, Long accountId) {
        RecipeVersion v = recipeRepo.findVersionById(versionId);
        if (v == null || !LedgerType.VER_PUBLISHED.equals(v.status())) {
            throw ApiException.notFound("已发布的配方版本不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> rows = new java.util.ArrayList<>();
        boolean enough = true;
        for (RecipeMaterial m : recipeRepo.findMaterials(v.id())) {
            Inventory inv = inventoryRepo.find(accountId, m.itemId())
                    .orElseGet(() -> new Inventory(null, accountId, m.itemId(), 0, 0, null));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", itemCode(m.itemId()));
            row.put("itemName", itemName(m.itemId()));
            row.put("need", m.qty());
            row.put("totalQty", inv.totalQty());
            row.put("heldQty", inv.heldQty());
            row.put("availableQty", inv.available());
            row.put("sufficient", inv.available() >= m.qty());
            if (inv.available() < m.qty()) {
                enough = false;
            }
            rows.add(row);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("versionId", v.id());
        view.put("versionNo", v.versionNo());
        view.put("outputItemCode", itemCode(v.outputItemId()));
        view.put("outputItemName", itemName(v.outputItemId()));
        view.put("outputQty", v.outputQty());
        view.put("eventStartsAt", v.eventStartsAt());
        view.put("eventEndsAt", v.eventEndsAt());
        view.put("craftSeconds", v.craftSeconds());
        view.put("timeoutSeconds", v.timeoutSeconds());
        view.put("eventOpen", !now.isBefore(v.eventStartsAt()) && now.isBefore(v.eventEndsAt()));
        view.put("sufficient", enough);
        view.put("materials", rows);
        return view;
    }

    @Transactional(readOnly = true)
    public List<RevokeException> exceptions(boolean all) {
        return all ? exceptionRepo.findAll() : exceptionRepo.findOpen();
    }

    private String itemName(Long itemId) {
        return itemRepo.findAll().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .map(i -> i.name() + "(" + i.code() + ")").orElse(String.valueOf(itemId));
    }

    private String itemCode(Long itemId) {
        return itemRepo.findAll().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .map(com.example.craft.domain.Item::code).orElse(String.valueOf(itemId));
    }

    private String newOrderNo() {
        return "C" + System.currentTimeMillis() + ThreadLocalRandom.current().nextInt(100000, 999999);
    }
}
