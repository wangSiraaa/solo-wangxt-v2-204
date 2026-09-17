package com.example.craft.service;

import com.example.craft.domain.Item;
import com.example.craft.domain.LedgerType;
import com.example.craft.domain.Recipe;
import com.example.craft.domain.RecipeMaterial;
import com.example.craft.domain.RecipeVersion;
import com.example.craft.repo.ItemRepo;
import com.example.craft.repo.RecipeRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配方发布规则：
 * 1. 已发布版本不可变——要改只能基于它新建版本（DRAFT）再发布；
 * 2. 同一配方同时至多一个 PUBLISHED，发布时旧版本原子地变 SUPERSEDED；
 * 3. 合成单外键指向具体版本 id，已经开始（预占）的合成始终按当时版本完成，与后续发布无关。
 */
@Service
public class RecipeService {

    private final RecipeRepo recipeRepo;
    private final ItemRepo itemRepo;

    public RecipeService(RecipeRepo recipeRepo, ItemRepo itemRepo) {
        this.recipeRepo = recipeRepo;
        this.itemRepo = itemRepo;
    }

    public record MaterialInput(String itemCode, int qty) {}

    public record DraftInput(String recipeCode, String recipeName, String outputItemCode, int outputQty,
                             LocalDateTime eventStartsAt, LocalDateTime eventEndsAt,
                             boolean autoComplete, int craftSeconds, int timeoutSeconds,
                             String changelog, List<MaterialInput> materials) {}

    @Transactional
    public Map<String, Object> createDraft(DraftInput in, String operator) {
        validate(in);

        Recipe recipe = recipeRepo.findRecipeByCode(in.recipeCode()).orElse(null);
        long recipeId;
        int nextVersion;
        if (recipe == null) {
            if (in.recipeName() == null || in.recipeName().isBlank()) {
                throw ApiException.badRequest("新配方必须提供 recipeName");
            }
            recipeId = recipeRepo.insertRecipe(in.recipeCode(), in.recipeName().trim());
            nextVersion = 1;
        } else {
            recipeId = recipe.id();
            // 锁配方行，串行化版本号分配
            recipeRepo.lockRecipeForUpdate(recipeId);
            List<RecipeVersion> existing = recipeRepo.findVersions(recipeId);
            nextVersion = existing.stream().mapToInt(RecipeVersion::versionNo).max().orElse(0) + 1;
        }

        RecipeVersion published = recipeRepo.findPublishedByRecipeIdNoLock(recipeId);
        if (published != null && !isChanged(published, in)) {
            throw ApiException.conflict("内容与当前已发布版本 v" + published.versionNo()
                    + " 完全一致，发布后如需修改请创建有差异的新版本");
        }

        Item output = itemRepo.findByCode(in.outputItemCode())
                .orElseThrow(() -> ApiException.badRequest("产出道具不存在: " + in.outputItemCode()));

        long versionId = recipeRepo.insertDraft(recipeId, nextVersion, output.id(), in.outputQty(),
                in.eventStartsAt(), in.eventEndsAt(), in.autoComplete(), in.craftSeconds(),
                in.timeoutSeconds(), in.changelog(), operator);

        List<Map<String, Object>> matViews = new ArrayList<>();
        List<MaterialInput> sorted = in.materials().stream()
                .sorted(Comparator.comparing(MaterialInput::itemCode))
                .toList();
        int seq = 1;
        for (MaterialInput m : sorted) {
            Item mat = itemRepo.findByCode(m.itemCode())
                    .orElseThrow(() -> ApiException.badRequest("材料道具不存在: " + m.itemCode()));
            recipeRepo.addMaterial(versionId, mat.id(), m.qty(), seq++);
            matViews.add(Map.of("itemCode", mat.code(), "itemName", mat.name(), "qty", m.qty()));
        }

        Map<String, Object> view = new LinkedHashMap<>();
        view.put("recipeCode", in.recipeCode());
        view.put("recipeName", recipe == null ? in.recipeName() : recipe.name());
        view.put("versionNo", nextVersion);
        view.put("versionId", versionId);
        view.put("status", LedgerType.VER_DRAFT);
        view.put("outputItemCode", in.outputItemCode());
        view.put("outputQty", in.outputQty());
        view.put("materials", matViews);
        return view;
    }

    @Transactional
    public Map<String, Object> publish(long versionId, String operator) {
        RecipeVersion v = recipeRepo.findVersionById(versionId);
        if (v == null) {
            throw ApiException.notFound("配方版本不存在: " + versionId);
        }
        if (!LedgerType.VER_DRAFT.equals(v.status())) {
            throw ApiException.conflict("仅 DRAFT 版本可发布，当前状态: " + v.status());
        }
        RecipeVersion old = recipeRepo.findPublishedByRecipeId(v.recipeId());
        recipeRepo.publish(old == null ? null : old.id(), versionId);
        RecipeVersion now = recipeRepo.findVersionById(versionId);
        return versionView(now);
    }

    public List<Map<String, Object>> listRecipes(boolean includeAllVersions) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Recipe r : recipeRepo.findAllRecipes()) {
            List<RecipeVersion> versions = recipeRepo.findVersions(r.id());
            if (versions.isEmpty()) {
                continue;
            }
            List<RecipeVersion> shown = includeAllVersions
                    ? versions
                    : versions.stream().filter(v -> !LedgerType.VER_DRAFT.equals(v.status())).toList();
            for (RecipeVersion v : shown) {
                Map<String, Object> mv = versionView(v);
                mv.put("recipeCode", r.code());
                mv.put("recipeName", r.name());
                out.add(mv);
            }
        }
        return out;
    }

    public Map<String, Object> versionView(RecipeVersion v) {
        java.util.Map<Long, Item> itemMap = new java.util.HashMap<>();
        for (Item i : itemRepo.findAll()) {
            itemMap.put(i.id(), i);
        }
        Item output = java.util.Objects.requireNonNull(itemMap.get(v.outputItemId()),
                () -> "output item missing: " + v.outputItemId());
        List<Map<String, Object>> materials = new ArrayList<>();
        for (RecipeMaterial m : recipeRepo.findMaterials(v.id())) {
            Item mi = java.util.Objects.requireNonNull(itemMap.get(m.itemId()),
                    () -> "material item missing: " + m.itemId());
            materials.add(Map.of("itemCode", mi.code(), "itemName", mi.name(), "qty", m.qty()));
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("versionId", v.id());
        map.put("recipeId", v.recipeId());
        map.put("versionNo", v.versionNo());
        map.put("status", v.status());
        map.put("outputItemCode", output.code());
        map.put("outputItemName", output.name());
        map.put("outputQty", v.outputQty());
        map.put("eventStartsAt", v.eventStartsAt());
        map.put("eventEndsAt", v.eventEndsAt());
        map.put("autoComplete", v.autoComplete());
        map.put("craftSeconds", v.craftSeconds());
        map.put("timeoutSeconds", v.timeoutSeconds());
        map.put("changelog", v.changelog());
        map.put("createdBy", v.createdBy());
        map.put("createdAt", v.createdAt());
        map.put("publishedAt", v.publishedAt());
        map.put("materials", materials);
        return map;
    }

    private boolean isChanged(RecipeVersion published, DraftInput in) {
        // 粗略判断：只要窗口/产出/耗时变了就算有差异；材料差异不允许静默覆盖，交由发布校验拦截。
        return !published.eventStartsAt().equals(in.eventStartsAt())
                || !published.eventEndsAt().equals(in.eventEndsAt())
                || published.craftSeconds() != in.craftSeconds()
                || published.timeoutSeconds() != in.timeoutSeconds()
                || published.outputQty() != in.outputQty()
                || published.autoComplete() != in.autoComplete();
    }

    private void validate(DraftInput in) {
        if (in.eventStartsAt() == null || in.eventEndsAt() == null) {
            throw ApiException.badRequest("活动开始/结束时间必填");
        }
        if (!in.eventEndsAt().isAfter(in.eventStartsAt())) {
            throw ApiException.badRequest("活动结束时间必须晚于开始时间");
        }
        if (in.outputQty() <= 0) {
            throw ApiException.badRequest("产出数量必须大于 0");
        }
        if (in.craftSeconds() <= 0 || in.timeoutSeconds() <= in.craftSeconds()) {
            throw ApiException.badRequest("合成耗时必须为正，且超时秒数必须大于合成耗时");
        }
        if (in.materials() == null || in.materials().isEmpty()) {
            throw ApiException.badRequest("至少配置一种材料");
        }
        long distinct = in.materials().stream().map(MaterialInput::itemCode).distinct().count();
        if (distinct != in.materials().size()) {
            throw ApiException.badRequest("材料行不可重复");
        }
        for (MaterialInput m : in.materials()) {
            if (m.qty() <= 0) {
                throw ApiException.badRequest("材料数量必须大于 0: " + m.itemCode());
            }
        }
    }
}
