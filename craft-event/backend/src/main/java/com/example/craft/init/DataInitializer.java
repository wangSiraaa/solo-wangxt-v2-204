package com.example.craft.init;

import com.example.craft.domain.Item;
import com.example.craft.domain.LedgerType;
import com.example.craft.repo.AccountRepo;
import com.example.craft.repo.InventoryRepo;
import com.example.craft.repo.ItemRepo;
import com.example.craft.repo.LedgerRepo;
import com.example.craft.repo.RecipeRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 幂等播种：仅当 account 表为空时写入演示数据。
 * 运营账号 ops/ops123；玩家 alice/alice123、bob/bob123。
 */
@Configuration
public class DataInitializer {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private record SeedItem(String code, String name) {}

    private record SeedRecipe(String code, String name, String output, int outputQty,
                              boolean autoComplete, int craftSeconds, int timeoutSeconds,
                              Map<String, Integer> materials,
                              long startsOffsetSec, long endsOffsetSec,
                              String initialStatus, String changelog) {}

    @Bean
    CommandLineRunner seeder(AccountRepo accountRepo, ItemRepo itemRepo, InventoryRepo inventoryRepo,
                             LedgerRepo ledgerRepo, RecipeRepo recipeRepo, JdbcTemplate jdbc,
                             BCryptPasswordEncoder encoder, TransactionTemplate tx) {
        return args -> {
            if (accountRepo.count() > 0) {
                log.info("seed skipped: data already present");
                return;
            }
            tx.executeWithoutResult(status -> {
                // ---- 账号 ----
                accountRepo.insert("ops", encoder.encode("ops123"), "运营小林", "OPERATOR");
                accountRepo.insert("alice", encoder.encode("alice123"), "玩家爱丽丝", "PLAYER");
                accountRepo.insert("bob", encoder.encode("bob123"), "玩家鲍勃", "PLAYER");
                long opsId = accountRepo.findByUsername("ops").orElseThrow().id();
                long aliceId = accountRepo.findByUsername("alice").orElseThrow().id();
                long bobId = accountRepo.findByUsername("bob").orElseThrow().id();

                // ---- 道具 ----
                List<SeedItem> items = List.of(
                        new SeedItem("WOOD", "原木"),
                        new SeedItem("STONE", "石块"),
                        new SeedItem("IRON", "铁矿"),
                        new SeedItem("GEM", "宝石"),
                        new SeedItem("HERB", "月光草"),
                        new SeedItem("FEATHER", "风之羽"),
                        new SeedItem("STARDUST", "星尘"),
                        new SeedItem("SCROLL", "残页卷轴"),
                        new SeedItem("CHEST", "秘银宝箱"),
                        new SeedItem("GIFT", "星尘福袋"),
                        new SeedItem("POTION", "手工合剂"),
                        new SeedItem("OLDBOX", "旧版礼盒")
                );
                for (SeedItem i : items) {
                    itemRepo.insert(i.code(), i.name());
                }
                java.util.function.Function<String, Long> itemId = code ->
                        itemRepo.findByCode(code).orElseThrow().id();

                // ---- 背包（总量, 预占初始为0）+ INIT 流水 ----
                record Stock(long accountId, String item, int qty) {}
                List<Stock> stocks = List.of(
                        // alice：GEM 仅 1 份 —— “最后一份材料”并发合成的关键数据
                        new Stock(aliceId, "WOOD", 50),
                        new Stock(aliceId, "STONE", 30),
                        new Stock(aliceId, "IRON", 20),
                        new Stock(aliceId, "GEM", 1),
                        new Stock(aliceId, "HERB", 12),
                        new Stock(aliceId, "FEATHER", 8),
                        new Stock(aliceId, "SCROLL", 5),
                        new Stock(aliceId, "STARDUST", 6),
                        new Stock(aliceId, "CHEST", 0),
                        new Stock(aliceId, "GIFT", 0),
                        new Stock(aliceId, "POTION", 0),
                        new Stock(aliceId, "OLDBOX", 0),
                        // bob：余量充足
                        new Stock(bobId, "WOOD", 40),
                        new Stock(bobId, "STONE", 40),
                        new Stock(bobId, "IRON", 10),
                        new Stock(bobId, "GEM", 9),
                        new Stock(bobId, "HERB", 20),
                        new Stock(bobId, "FEATHER", 6),
                        new Stock(bobId, "SCROLL", 3),
                        new Stock(bobId, "STARDUST", 4),
                        new Stock(bobId, "CHEST", 0),
                        new Stock(bobId, "GIFT", 0),
                        new Stock(bobId, "POTION", 0),
                        new Stock(bobId, "OLDBOX", 0)
                );
                for (Stock s : stocks) {
                    long iid = itemId.apply(s.item());
                    inventoryRepo.insertIfAbsent(s.accountId(), iid, s.qty());
                    if (s.qty() > 0) {
                        ledgerRepo.append(s.accountId(), iid, LedgerType.L_INIT, s.qty(), 0,
                                s.qty(), 0, LedgerType.REF_SEED, "SEED", null, "初始背包");
                    }
                }

                // ---- 配方（时间窗口相对于服务启动时刻）----
                LocalDateTime t0 = LocalDateTime.now().withNano(0);
                List<SeedRecipe> recipes = List.of(
                        // 自动完成：5 秒合成，15 秒超时
                        new SeedRecipe("R001", "秘银宝箱", "CHEST", 1, true, 5, 15,
                                Map.of("WOOD", 3, "IRON", 2, "GEM", 1),
                                -300, 3600, "PUBLISHED", "活动首版：3 原木 + 2 铁矿 + 1 宝石"),
                        // 自动完成：3 秒合成，10 秒超时
                        new SeedRecipe("R002", "星尘福袋", "GIFT", 2, true, 3, 10,
                                Map.of("STARDUST", 2, "HERB", 2, "FEATHER", 1),
                                -300, 3600, "PUBLISHED", "限时福袋：2 星尘 + 2 月光草 + 1 风之羽"),
                        // 手动领取：合成 2 秒，领取窗口 8 秒；不领取则第 10 秒超时释放
                        new SeedRecipe("R003", "炼金实验·手动领取", "POTION", 1, false, 2, 10,
                                Map.of("HERB", 2, "SCROLL", 1),
                                -300, 3600, "PUBLISHED", "需玩家手动领取，用于观察超时取消释放占用"),
                        // 已结束活动：服务端必须拒绝新合成
                        new SeedRecipe("R900", "往期礼盒（活动已结束）", "OLDBOX", 1, true, 2, 10,
                                Map.of("WOOD", 1),
                                -7200, -60, "PUBLISHED", "历史活动，仅用于验证活动结束拦截")
                );

                for (SeedRecipe r : recipes) {
                    long recipeId = recipeRepo.insertRecipe(r.code(), r.name());
                    long versionId = recipeRepo.insertDraft(recipeId, 1, itemId.apply(r.output()),
                            r.outputQty(), t0.plusSeconds(r.startsOffsetSec()), t0.plusSeconds(r.endsOffsetSec()),
                            r.autoComplete(), r.craftSeconds(), r.timeoutSeconds(), r.changelog(), "ops");
                    List<String> sorted = r.materials().keySet().stream().sorted().toList();
                    int seq = 1;
                    for (String code : sorted) {
                        recipeRepo.addMaterial(versionId, itemId.apply(code), r.materials().get(code), seq++);
                    }
                    if ("PUBLISHED".equals(r.initialStatus())) {
                        recipeRepo.publish(null, versionId);
                    }
                }
                log.info("seed completed: accounts(ops/ops123, alice/alice123, bob/bob123), 12 items, 4 recipes");
            });
        };
    }
}
