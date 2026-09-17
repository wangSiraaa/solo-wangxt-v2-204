package com.example.craft.web;

import com.example.craft.domain.Item;
import com.example.craft.domain.RevokeException;
import com.example.craft.repo.ItemRepo;
import com.example.craft.repo.OrderRepo;
import com.example.craft.service.CraftService;
import com.example.craft.service.RecipeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/operator")
public class OperatorController {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final RecipeService recipeService;
    private final CraftService craftService;
    private final ItemRepo itemRepo;
    private final OrderRepo orderRepo;

    public OperatorController(RecipeService recipeService, CraftService craftService,
                              ItemRepo itemRepo, OrderRepo orderRepo) {
        this.recipeService = recipeService;
        this.craftService = craftService;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
    }

    @GetMapping("/items")
    public List<Item> items() {
        return itemRepo.findAll();
    }

    /** 配方全版本视图（操作台展示“配方版本”）。 */
    @GetMapping("/recipes")
    public List<Map<String, Object>> recipes(@RequestParam(defaultValue = "true") boolean allVersions) {
        return recipeService.listRecipes(allVersions);
    }

    /** 创建新版本草稿（已发布配方不可改，只能新建版本）。 */
    @PostMapping("/recipes/drafts")
    public Map<String, Object> createDraft(@RequestBody Map<String, Object> body) {
        RecipeService.DraftInput in = parseDraft(body);
        return recipeService.createDraft(in, CurrentUser.get().username());
    }

    /** 发布草稿：旧 PUBLISHED 原子切换 SUPERSEDED；进行中的合成仍绑定旧版本。 */
    @PostMapping("/recipes/versions/{versionId}/publish")
    public Map<String, Object> publish(@PathVariable long versionId) {
        return recipeService.publish(versionId, CurrentUser.get().username());
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (var o : orderRepo.findAll()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("orderNo", o.orderNo());
            row.put("accountId", o.accountId());
            row.put("versionId", o.recipeVersionId());
            row.put("snapshotVersion", o.snapshotVersion());
            row.put("outputItemId", o.outputItemId());
            row.put("outputQty", o.outputQty() * o.qty());
            row.put("qty", o.qty());
            row.put("status", o.status());
            row.put("autoComplete", o.autoComplete());
            row.put("heldFrom", o.heldFrom());
            row.put("completeAt", o.completeAt());
            row.put("expireAt", o.expireAt());
            row.put("completedAt", o.completedAt());
            row.put("cancelledAt", o.cancelledAt());
            row.put("cancelReason", o.cancelReason());
            row.put("revokedAt", o.revokedAt());
            row.put("revokeOperator", o.revokeOperator());
            row.put("requestId", o.requestId());
            out.add(row);
        }
        return out;
    }

    /** 撤销错误奖励：生成反向流水；材料已使用则进异常清单。 */
    @PostMapping("/orders/{orderNo}/revoke")
    public Map<String, Object> revoke(@PathVariable String orderNo) {
        return craftService.revoke(orderNo, CurrentUser.get().username());
    }

    @GetMapping("/exceptions")
    public List<Map<String, Object>> exceptions(@RequestParam(defaultValue = "false") boolean all) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RevokeException e : craftService.exceptions(all)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("exceptionNo", e.exceptionNo());
            row.put("orderNo", e.orderNo());
            row.put("accountId", e.accountId());
            row.put("reason", e.reason());
            row.put("missingItemId", e.missingItemId());
            row.put("missingQty", e.missingQty());
            row.put("status", e.status());
            row.put("remark", e.remark());
            row.put("createdBy", e.createdBy());
            row.put("createdAt", e.createdAt());
            row.put("resolvedAt", e.resolvedAt());
            row.put("resolvedBy", e.resolvedBy());
            out.add(row);
        }
        return out;
    }

    @PostMapping("/exceptions/{exceptionNo}/resolve")
    public Map<String, Object> resolve(@PathVariable String exceptionNo, @RequestBody(required = false) Map<String, String> body) {
        craftService.resolveException(exceptionNo, CurrentUser.get().username(),
                body == null ? null : body.get("remark"));
        return Map.of("ok", true);
    }

    @SuppressWarnings("unchecked")
    private RecipeService.DraftInput parseDraft(Map<String, Object> body) {
        List<RecipeService.MaterialInput> mats = new ArrayList<>();
        for (Map<String, Object> m : (List<Map<String, Object>>) body.getOrDefault("materials", List.of())) {
            mats.add(new RecipeService.MaterialInput(
                    String.valueOf(m.get("itemCode")),
                    Integer.parseInt(String.valueOf(m.get("qty")))));
        }
        return new RecipeService.DraftInput(
                str(body, "recipeCode"),
                str(body, "recipeName"),
                str(body, "outputItemCode"),
                Integer.parseInt(String.valueOf(body.getOrDefault("outputQty", "1"))),
                LocalDateTime.parse(str(body, "eventStartsAt"), FMT),
                LocalDateTime.parse(str(body, "eventEndsAt"), FMT),
                Boolean.parseBoolean(String.valueOf(body.getOrDefault("autoComplete", "true"))),
                Integer.parseInt(String.valueOf(body.get("craftSeconds"))),
                Integer.parseInt(String.valueOf(body.get("timeoutSeconds"))),
                str(body, "changelog"),
                mats);
    }

    private String str(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? null : String.valueOf(v);
    }
}
