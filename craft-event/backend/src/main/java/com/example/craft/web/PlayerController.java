package com.example.craft.web;

import com.example.craft.domain.CraftOrder;
import com.example.craft.domain.Inventory;
import com.example.craft.domain.Item;
import com.example.craft.domain.LedgerEntry;
import com.example.craft.domain.LedgerType;
import com.example.craft.domain.MaterialHold;
import com.example.craft.repo.HoldRepo;
import com.example.craft.repo.InventoryRepo;
import com.example.craft.repo.ItemRepo;
import com.example.craft.repo.LedgerRepo;
import com.example.craft.repo.OrderRepo;
import com.example.craft.repo.RecipeRepo;
import com.example.craft.service.CraftService;
import com.example.craft.service.RecipeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/player")
public class PlayerController {

    private final CraftService craftService;
    private final RecipeService recipeService;
    private final RecipeRepo recipeRepo;
    private final ItemRepo itemRepo;
    private final InventoryRepo inventoryRepo;
    private final OrderRepo orderRepo;
    private final HoldRepo holdRepo;
    private final LedgerRepo ledgerRepo;

    public PlayerController(CraftService craftService, RecipeService recipeService, RecipeRepo recipeRepo,
                            ItemRepo itemRepo, InventoryRepo inventoryRepo, OrderRepo orderRepo,
                            HoldRepo holdRepo, LedgerRepo ledgerRepo) {
        this.craftService = craftService;
        this.recipeService = recipeService;
        this.recipeRepo = recipeRepo;
        this.itemRepo = itemRepo;
        this.inventoryRepo = inventoryRepo;
        this.orderRepo = orderRepo;
        this.holdRepo = holdRepo;
        this.ledgerRepo = ledgerRepo;
    }

    /** 已发布配方列表（含版本号，供操作台展示与提交时携带）。 */
    @GetMapping("/recipes")
    public List<Map<String, Object>> recipes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var v : recipeRepo.findPublishedVersionsForPlayers()) {
            Map<String, Object> view = recipeService.versionView(v);
            view.put("recipeCode", recipeCode(v.recipeId()));
            out.add(view);
        }
        return out;
    }

    /** 合成预览：逐材料的需求、持有、预占、可用。 */
    @GetMapping("/recipes/{versionId}/preview")
    public Map<String, Object> preview(@PathVariable long versionId) {
        return craftService.preview(versionId, CurrentUser.id());
    }

    /** 发起合成。服务端重新判定一切（活动时间/材料余量/版本状态），不依赖按钮置灰。 */
    @PostMapping("/craft")
    public Map<String, Object> craft(@RequestBody Map<String, Object> body) {
        long versionId = Long.parseLong(String.valueOf(body.get("versionId")));
        int qty = Integer.parseInt(String.valueOf(body.getOrDefault("qty", 1)));
        String requestId = String.valueOf(body.getOrDefault("requestId", ""));
        CraftOrder order = craftService.craft(CurrentUser.id(), versionId, qty, requestId);
        return orderView(order);
    }

    /** 完成合成（领取）。对已终态单据幂等，重复点击不会重复发奖。 */
    @PostMapping("/orders/{orderNo}/complete")
    public Map<String, Object> complete(@PathVariable String orderNo) {
        CraftOrder order = craftService.complete(CurrentUser.id(), orderNo);
        return orderView(order);
    }

    /** 玩家主动取消，释放预占。 */
    @PostMapping("/orders/{orderNo}/cancel")
    public Map<String, Object> cancel(@PathVariable String orderNo) {
        CraftOrder order = craftService.cancel(CurrentUser.id(), orderNo);
        return orderView(order);
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return orderRepo.findByAccount(CurrentUser.id()).stream().map(this::orderView).toList();
    }

    @GetMapping("/orders/{orderNo}")
    public Map<String, Object> orderDetail(@PathVariable String orderNo) {
        CraftOrder order = orderRepo.findByOrderNo(orderNo)
                .orElseThrow(() -> com.example.craft.service.ApiException.notFound("合成单不存在"));
        if (!order.accountId().equals(CurrentUser.id())) {
            throw com.example.craft.service.ApiException.forbidden("无权查看他人合成单");
        }
        Map<String, Object> view = orderView(order);
        view.put("holds", holdViews(order.orderNo()));
        view.put("ledger", ledgerRepo.findByRef(LedgerType.REF_ORDER, orderNo).stream()
                .map(this::ledgerView).toList());
        return view;
    }

    /** 背包：总量 / 预占 / 可用。 */
    @GetMapping("/inventory")
    public List<Map<String, Object>> inventory() {
        Map<Long, String> codeName = new LinkedHashMap<>();
        for (Item i : itemRepo.findAll()) {
            codeName.put(i.id(), i.code() + "/" + i.name());
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inventory inv : inventoryRepo.findForAccount(CurrentUser.id())) {
            if (inv.totalQty() == 0) {
                continue;
            }
            Item item = itemRepo.findAll().stream().filter(i -> i.id().equals(inv.itemId())).findFirst().orElse(null);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", item == null ? inv.itemId() : item.code());
            row.put("itemName", item == null ? String.valueOf(inv.itemId()) : item.name());
            row.put("totalQty", inv.totalQty());
            row.put("heldQty", inv.heldQty());
            row.put("availableQty", inv.available());
            out.add(row);
        }
        return out;
    }

    /** 逐笔材料去向流水（只追加账本）。 */
    @GetMapping("/ledger")
    public List<Map<String, Object>> ledger() {
        java.util.Map<Long, Item> itemMap = new java.util.HashMap<>();
        for (Item i : itemRepo.findAll()) itemMap.put(i.id(), i);
        return ledgerRepo.findByAccount(CurrentUser.id()).stream().map(e -> ledgerView(e, itemMap)).toList();
    }

    @GetMapping("/holds")
    public List<Map<String, Object>> holds() {
        return holdRepo.findByAccount(CurrentUser.id()).stream().map(h -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", h.id());
            row.put("orderNo", h.orderNo());
            row.put("itemCode", itemCode(h.itemId()));
            row.put("itemName", itemName(h.itemId()));
            row.put("qty", h.qty());
            row.put("status", h.status());
            row.put("createdAt", h.createdAt());
            row.put("consumedAt", h.consumedAt());
            row.put("releasedAt", h.releasedAt());
            row.put("reversedAt", h.reversedAt());
            return row;
        }).toList();
    }

    // ---------- view helpers ----------

    private Map<String, Object> orderView(CraftOrder o) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("orderNo", o.orderNo());
        m.put("recipeCode", recipeCode(o.recipeId()));
        m.put("versionId", o.recipeVersionId());
        m.put("snapshotVersion", o.snapshotVersion());
        m.put("outputItemCode", itemCode(o.outputItemId()));
        m.put("outputItemName", itemName(o.outputItemId()));
        m.put("outputQty", o.outputQty() * o.qty());
        m.put("qty", o.qty());
        m.put("status", o.status());
        m.put("autoComplete", o.autoComplete());
        m.put("heldFrom", o.heldFrom());
        m.put("completeAt", o.completeAt());
        m.put("expireAt", o.expireAt());
        m.put("completedAt", o.completedAt());
        m.put("cancelledAt", o.cancelledAt());
        m.put("cancelReason", o.cancelReason());
        m.put("revokedAt", o.revokedAt());
        m.put("revokeOperator", o.revokeOperator());
        m.put("requestId", o.requestId());
        m.put("createdAt", o.createdAt());
        return m;
    }

    private List<Map<String, Object>> holdViews(String orderNo) {
        return holdRepo.findByOrderNoReadOnly(orderNo).stream().map(h -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemCode", itemCode(h.itemId()));
            row.put("itemName", itemName(h.itemId()));
            row.put("qty", h.qty());
            row.put("status", h.status());
            row.put("createdAt", h.createdAt());
            row.put("consumedAt", h.consumedAt());
            row.put("releasedAt", h.releasedAt());
            row.put("reversedAt", h.reversedAt());
            return row;
        }).toList();
    }

    private Map<String, Object> ledgerView(LedgerEntry e) {
        java.util.Map<Long, Item> itemMap = new java.util.HashMap<>();
        for (Item i : itemRepo.findAll()) itemMap.put(i.id(), i);
        return ledgerView(e, itemMap);
    }

    private Map<String, Object> ledgerView(LedgerEntry e, java.util.Map<Long, Item> itemMap) {
        Item item = itemMap.get(e.itemId());
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", e.id());
        row.put("itemCode", item == null ? e.itemId() : item.code());
        row.put("itemName", item == null ? String.valueOf(e.itemId()) : item.name());
        row.put("changeType", e.changeType());
        row.put("qtyChange", e.qtyChange());
        row.put("heldDelta", e.heldDelta());
        row.put("balanceTotal", e.balanceTotal());
        row.put("balanceHeld", e.balanceHeld());
        row.put("refType", e.refType());
        row.put("refNo", e.refNo());
        row.put("reversalOf", e.reversalOf());
        row.put("remark", e.remark());
        row.put("createdAt", e.createdAt());
        return row;
    }

    private String recipeCode(long recipeId) {
        return recipeRepo.findAllRecipes().stream().filter(r -> r.id() == recipeId).findFirst()
                .map(com.example.craft.domain.Recipe::code).orElse(String.valueOf(recipeId));
    }

    private String itemCode(Long itemId) {
        return itemRepo.findAll().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .map(Item::code).orElse(String.valueOf(itemId));
    }

    private String itemName(Long itemId) {
        return itemRepo.findAll().stream().filter(i -> i.id().equals(itemId)).findFirst()
                .map(Item::name).orElse(String.valueOf(itemId));
    }
}
