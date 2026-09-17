package com.example.craft;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 针对真实 InnoDB（开发环境 MariaDB 10.11，行锁语义同 MySQL）的并发合成测试。
 * 数据使用独立配方/独立道具，避免与其他测试互相干扰；背包/流水由测试自行播种。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CraftConcurrencyIT {

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.example.craft.service.CraftService craftService;

    private String base() {
        return "http://localhost:" + port;
    }

    private String token(String username, String password) {
        Map<String, Object> resp = rest.postForObject(base() + "/api/auth/login",
                Map.of("username", username, "password", password), Map.class);
        return (String) resp.get("token");
    }

    private HttpHeaders auth(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private long aliceId() {
        return jdbc.queryForObject("SELECT id FROM account WHERE username='alice'", Long.class);
    }

    /** 创建一个消耗指定材料 1 份的限时配方（自动/手动可选），返回版本 id。 */
    private long setupRecipe(String unique, String materialCode, int craftSeconds, int timeoutSeconds,
                             String outputCode, boolean autoComplete, long startOffset, long endOffset) {
        long matId = jdbc.queryForObject("SELECT id FROM item WHERE code=?", Long.class, materialCode);
        long outId = jdbc.queryForObject("SELECT id FROM item WHERE code=?", Long.class, outputCode);
        org.springframework.jdbc.support.KeyHolder kh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recipe(code,name) VALUES (?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, "T_" + unique);
            ps.setString(2, "测试配方" + unique);
            return ps;
        }, kh);
        long recipeId = kh.getKey().longValue();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        org.springframework.jdbc.support.KeyHolder vkh = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(con -> {
            java.sql.PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recipe_version(recipe_id, version_no, output_item_id, output_qty, " +
                            "event_starts_at, event_ends_at, auto_complete, craft_seconds, timeout_seconds, " +
                            "status, changelog, created_by, published_at) " +
                            "VALUES (?,1,?,1,?,?,?,?,?,'PUBLISHED','test','tester',NOW(3))",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, recipeId);
            ps.setLong(2, outId);
            ps.setTimestamp(3, java.sql.Timestamp.valueOf(now.plusSeconds(startOffset)));
            ps.setTimestamp(4, java.sql.Timestamp.valueOf(now.plusSeconds(endOffset)));
            ps.setInt(5, autoComplete ? 1 : 0);
            ps.setInt(6, craftSeconds);
            ps.setInt(7, timeoutSeconds);
            return ps;
        }, vkh);
        long versionId = vkh.getKey().longValue();
        jdbc.update("INSERT INTO recipe_material(recipe_version_id,item_id,qty,seq_no) VALUES (?,?,1,1)",
                versionId, matId);
        return versionId;
    }

    private long itemId(String code) {
        return jdbc.queryForObject("SELECT id FROM item WHERE code=?", Long.class, code);
    }

    private void giveStock(long accountId, String code, int total) {
        long iid = itemId(code);
        jdbc.update("INSERT IGNORE INTO inventory(account_id,item_id,total_qty,held_qty) VALUES (?,?,?,0)",
                accountId, iid, total);
        jdbc.update("UPDATE inventory SET total_qty=?, held_qty=0 WHERE account_id=? AND item_id=?",
                total, accountId, iid);
    }

    /** 每次运行唯一的道具码，避免跨 mvn 运行的同名数据残留影响断言。 */
    private String newItem(String base, String name) {
        String code = base + "_" + Long.toString(System.nanoTime(), 36).toUpperCase();
        jdbc.update("INSERT INTO item(code,name) VALUES (?,?)", code, name);
        return code;
    }

    private int[] stock(long accountId, String code) {
        return jdbc.queryForObject("SELECT total_qty, held_qty FROM inventory WHERE account_id=? AND item_id=?",
                (rs, n) -> new int[]{rs.getInt(1), rs.getInt(2)}, accountId, itemId(code));
    }

    private int count(String sql, Object... args) {
        Integer c = jdbc.queryForObject(sql, Integer.class, args);
        return c == null ? 0 : c;
    }

    /** 轮询等待合成进入可领取窗口（craftSeconds 边界 + 秒级时间截断）。 */
    private com.example.craft.domain.CraftOrder completeWaiting(long accountId, String orderNo)
            throws InterruptedException {
        com.example.craft.service.ApiException last = null;
        for (int i = 0; i < 40; i++) {
            try {
                return craftService.complete(accountId, orderNo);
            } catch (com.example.craft.service.ApiException e) {
                last = e;
                if (e.getStatus().value() != 409 || !e.getMessage().contains("合成进行中")) {
                    throw e;
                }
                Thread.sleep(100);
            }
        }
        throw last;
    }

    // ========== 用例 1：最后一份材料被并发合成，恰好一单成功 ==========

    @Test
    void last_single_material_concurrent_craft_only_one_wins() throws Exception {
        String token = token("alice", "alice123");
        long aliceId = aliceId();
        // 独立稀缺材料 1 份
        String mat = newItem("IT_RARE", "稀缺材料");
        String prize = newItem("IT_PRIZE", "稀有奖励");
        giveStock(aliceId, mat, 1);
        long versionId = setupRecipe("RACE" + System.nanoTime(), mat, 2, 60,
                prize, true, -60, 3600);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String reqId = UUID.randomUUID().toString();
            futures.add(pool.submit(() -> {
                start.await();
                ResponseEntity<Map> resp = rest.exchange(base() + "/api/player/craft", HttpMethod.POST,
                        new HttpEntity<>(Map.of("versionId", versionId, "qty", 1, "requestId", reqId), auth(token)),
                        Map.class);
                return resp.getStatusCode().value();
            }));
        }
        start.countDown();
        int ok200 = 0, conflict409 = 0;
        for (Future<Integer> f : futures) {
            int code = f.get(20, TimeUnit.SECONDS);
            if (code == 200) ok200++;
            else if (code == 409) conflict409++;
            else fail("unexpected http status " + code);
        }
        pool.shutdown();
        assertEquals(1, ok200, "恰好一单成功");
        assertEquals(threads - 1, conflict409, "其余全部因材料不足被服务端拒绝");

        int[] s = stock(aliceId, mat);
        assertEquals(1, s[0], "总量不变（仅预占）");
        assertEquals(1, s[1], "仅预占 1 份，不能多占");

        int heldOrders = count("SELECT COUNT(*) FROM craft_order WHERE recipe_version_id=? AND status='HELD'",
                versionId);
        assertEquals(1, heldOrders);
        int holds = count("SELECT COUNT(*) FROM material_hold h JOIN craft_order o ON h.order_no=o.order_no " +
                "WHERE o.recipe_version_id=? AND h.status='HELD'", versionId);
        assertEquals(1, holds);
        int holdRows = count("SELECT COUNT(*) FROM item_ledger l JOIN craft_order o ON l.ref_no=o.order_no " +
                "WHERE o.recipe_version_id=? AND l.change_type='HOLD'", versionId);
        assertEquals(1, holdRows, "流水也只能有一笔预占");
    }

    // ========== 用例 2：请求重试（同 requestId）只产生一单 ==========

    @Test
    @SuppressWarnings("unchecked")
    void retried_same_request_id_is_idempotent() {
        String token = token("bob", "bob123");
        long bobId = jdbc.queryForObject("SELECT id FROM account WHERE username='bob'", Long.class);
        String mat = newItem("IT_IDEM", "幂等材料");
        String out = newItem("IT_IDEM_OUT", "幂等奖励");
        giveStock(bobId, mat, 10);
        long versionId = setupRecipe("IDEM" + System.nanoTime(), mat, 2, 60,
                out, true, -60, 3600);

        String requestId = UUID.randomUUID().toString();
        Map<String, Object> body = Map.of("versionId", versionId, "qty", 1, "requestId", requestId);
        ResponseEntity<Map> r1 = rest.exchange(base() + "/api/player/craft", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        ResponseEntity<Map> r2 = rest.exchange(base() + "/api/player/craft", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        ResponseEntity<Map> r3 = rest.exchange(base() + "/api/player/craft", HttpMethod.POST,
                new HttpEntity<>(body, auth(token)), Map.class);
        assertEquals(200, r1.getStatusCode().value());
        assertEquals(200, r2.getStatusCode().value());
        assertEquals(200, r3.getStatusCode().value());
        assertEquals(r1.getBody().get("orderNo"), r2.getBody().get("orderNo"));
        assertEquals(r1.getBody().get("orderNo"), r3.getBody().get("orderNo"));

        int orders = count("SELECT COUNT(*) FROM craft_order WHERE account_id=? AND request_id=?", bobId, requestId);
        assertEquals(1, orders);
        int[] s = stock(bobId, mat);
        assertEquals(1, s[1], "重试不重复预占");
    }

    // ========== 用例 3：完成与超时取消并发，绝不重复发奖或既扣又还 ==========

    @Test
    void complete_and_timeout_race_exactly_one_outcome() throws Exception {
        long aliceId = aliceId();
        String mat = newItem("IT_RACE", "赛跑材料");
        String out = newItem("IT_RACE_OUT", "赛跑奖励");
        giveStock(aliceId, mat, 1);
        // craft=2, timeout=10：在“已可领取、未超时”的宽窗口里让领取与超时扫描并发
        long versionId = setupRecipe("TIMER" + System.nanoTime(), mat, 2, 10,
                out, true, -60, 3600);

        String orderNo = craftService.craft(aliceId, versionId, 1, UUID.randomUUID().toString()).orderNo();

        // 进入领取窗口（complete_at 之后、expire_at 之前），留足余量避开秒级截断
        Thread.sleep(2300);
        LocalDateTime now = LocalDateTime.now().withNano(0);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> f1 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            craftService.complete(aliceId, orderNo);   // 领取方
        });
        Future<?> f2 = pool.submit(() -> {
            try {
                start.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            craftService.sweepTimeout(orderNo, now);    // 超时方（此刻未过期，应为 no-op）
        });
        start.countDown();
        f1.get(10, TimeUnit.SECONDS);
        f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        String status = jdbc.queryForObject("SELECT status FROM craft_order WHERE order_no=?",
                String.class, orderNo);
        assertEquals("COMPLETED", status, "领取窗口内领取方必须胜出，且只发一次奖");
        int produced = count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='PRODUCE'", orderNo);
        int released = count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='RELEASE'", orderNo);
        assertEquals(1, produced);
        assertEquals(0, released, "不能既发奖又释放");
        int[] s = stock(aliceId, mat);
        assertEquals(0, s[0]);
        assertEquals(0, s[1]);
        int[] p = stock(aliceId, out);
        assertEquals(1, p[0]);
    }

    @Test
    void expired_hold_is_released_and_cannot_be_completed() throws Exception {
        long aliceId = aliceId();
        String mat = newItem("IT_TO", "超时材料");
        String out = newItem("IT_TO_OUT", "超时奖励");
        giveStock(aliceId, mat, 1);
        // craft=1, timeout=5：不领取，等过 expire_at
        long versionId = setupRecipe("TO" + System.nanoTime(), mat, 1, 5,
                out, false, -60, 3600);

        String orderNo = craftService.craft(aliceId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        Thread.sleep(5300);
        LocalDateTime after = LocalDateTime.now().withNano(0);
        assertTrue(craftService.sweepTimeout(orderNo, after), "超时扫描应取消并释放");
        assertEquals("CANCELLED", jdbc.queryForObject(
                "SELECT status FROM craft_order WHERE order_no=?", String.class, orderNo));
        int[] s = stock(aliceId, mat);
        assertEquals(1, s[0], "材料归还");
        assertEquals(0, s[1], "占用归零");
        assertEquals(1, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='RELEASE'", orderNo));
        assertEquals(0, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='PRODUCE'", orderNo));

        // 超时后再领取：已超时取消，接口幂等回显 CANCELLED（状态机已终态，不产出）
        com.example.craft.domain.CraftOrder again = craftService.complete(aliceId, orderNo);
        assertEquals("CANCELLED", again.status());
        assertEquals(0, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='PRODUCE'", orderNo));

        // 超时扫描重入幂等
        assertFalse(craftService.sweepTimeout(orderNo, after.plusSeconds(10)));
        assertEquals(1, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='RELEASE'", orderNo),
                "释放流水只能一笔");
    }

    // ========== 用例 4：活动结束后，服务端拒绝新合成；已开始的按原版本完成 ==========

    @Test
    void event_ended_blocks_new_craft_but_existing_completes_on_snapshot_version() throws Exception {
        long aliceId = aliceId();
        String mat = newItem("IT_END", "末期材料");
        String out = newItem("IT_END_OUT", "末期奖励");
        giveStock(aliceId, mat, 5);
        // 3 秒后结束、合成耗时 1、超时 60：先抢进一单，再等活动结束
        long versionId = setupRecipe("END" + System.nanoTime(), mat, 1, 60,
                out, true, -60, 3);

        String token = token("alice", "alice123");
        ResponseEntity<Map> r1 = rest.exchange(base() + "/api/player/craft", HttpMethod.POST,
                new HttpEntity<>(Map.of("versionId", versionId, "qty", 1,
                        "requestId", UUID.randomUUID().toString()), auth(token)), Map.class);
        assertEquals(200, r1.getStatusCode().value());
        String orderNo = (String) r1.getBody().get("orderNo");

        Thread.sleep(3300); // 活动已结束
        com.example.craft.service.ApiException ex = assertThrows(com.example.craft.service.ApiException.class,
                () -> craftService.craft(aliceId, versionId, 1, UUID.randomUUID().toString()));
        assertEquals(409, ex.getStatus().value());
        assertTrue(ex.getMessage().contains("活动已结束"));

        // 等合成完成，用快照版本完成（与活动结束无关）
        var order = completeWaiting(aliceId, orderNo);
        assertEquals("COMPLETED", order.status());
        int produced = count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='PRODUCE'", orderNo);
        assertEquals(1, produced);
    }

    // ========== 用例 5：撤销错误奖励 —— 反向流水；材料已使用 → 异常清单 ==========

    @Test
    void revoke_produces_reversal_ledger_or_exception_when_consumed() throws Exception {
        long aliceId = aliceId();
        String token = token("alice", "alice123");
        String ops = token("ops", "ops123");
        String mat = newItem("IT_REV", "撤销材料");
        String out = newItem("IT_REV_OUT", "错误奖励");
        giveStock(aliceId, mat, 3);
        long versionId = setupRecipe("REV" + System.nanoTime(), mat, 1, 60,
                out, true, -60, 3600);

        String orderNo = craftService.craft(aliceId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        completeWaiting(aliceId, orderNo);
        assertEquals(1, stock(aliceId, out)[0]);
        assertEquals(2, stock(aliceId, mat)[0]);

        // 玩家把错误奖励“用掉”（直接模拟消耗到 0）
        jdbc.update("UPDATE inventory SET total_qty=0 WHERE account_id=? AND item_id=?", aliceId, itemId(out));

        ResponseEntity<Map> blocked = rest.exchange(base() + "/api/operator/orders/" + orderNo + "/revoke",
                HttpMethod.POST, new HttpEntity<>(Map.of(), auth(ops)), Map.class);
        assertEquals(200, blocked.getStatusCode().value());
        assertEquals(false, blocked.getBody().get("reversed"));
        String exNo = (String) blocked.getBody().get("exceptionNo");
        assertNotNull(exNo);
        Integer openEx = jdbc.queryForObject(
                "SELECT COUNT(*) FROM revoke_exception WHERE exception_no=? AND status='OPEN'",
                Integer.class, exNo);
        assertEquals(1, openEx);
        // 没有产生任何反向流水，订单仍 COMPLETED
        assertEquals(0, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='REVERSE_PRODUCE'",
                orderNo));
        assertEquals("COMPLETED",
                jdbc.queryForObject("SELECT status FROM craft_order WHERE order_no=?", String.class, orderNo));

        // 运营线下处理后关闭异常
        rest.exchange(base() + "/api/operator/exceptions/" + exNo + "/resolve", HttpMethod.POST,
                new HttpEntity<>(Map.of("remark", "已联系玩家线下补偿"), auth(ops)), Map.class);
        assertEquals("RESOLVED", jdbc.queryForObject(
                "SELECT status FROM revoke_exception WHERE exception_no=?", String.class, exNo));

        // 第二单：奖励未使用 → 完整反向流水 + 材料退还
        String orderNo2 = craftService.craft(aliceId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        completeWaiting(aliceId, orderNo2);
        assertEquals(1, stock(aliceId, out)[0]);
        ResponseEntity<Map> ok = rest.exchange(base() + "/api/operator/orders/" + orderNo2 + "/revoke",
                HttpMethod.POST, new HttpEntity<>(Map.of(), auth(ops)), Map.class);
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(true, ok.getBody().get("reversed"));
        assertEquals(0, stock(aliceId, out)[0], "奖励回收");
        assertEquals(2, stock(aliceId, mat)[0], "材料退还是 2（第一单消耗后一直没退）");
        assertEquals(1, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='REVERSE_PRODUCE'",
                orderNo2));
        assertEquals(1, count("SELECT COUNT(*) FROM item_ledger WHERE ref_no=? AND change_type='REVERSE_CONSUME'",
                orderNo2));
        assertEquals("REVOKED",
                jdbc.queryForObject("SELECT status FROM craft_order WHERE order_no=?", String.class, orderNo2));

        // 重复撤销必须被拒绝
        ResponseEntity<Map> again = rest.exchange(base() + "/api/operator/orders/" + orderNo2 + "/revoke",
                HttpMethod.POST, new HttpEntity<>(Map.of(), auth(ops)), Map.class);
        assertEquals(409, again.getStatusCode().value());
    }

    // ========== 用例 6：流水回放恒等 —— 余额只信账本 ==========

    @Test
    void ledger_replay_matches_inventory_after_all_operations() throws Exception {
        long bobId = jdbc.queryForObject("SELECT id FROM account WHERE username='bob'", Long.class);
        String mat = newItem("IT_SUM", "回放材料");
        String out = newItem("IT_SUM_OUT", "回放奖励");
        giveStock(bobId, mat, 5);
        long versionId = setupRecipe("SUM" + System.nanoTime(), mat, 1, 60,
                out, true, -60, 3600);

        String o1 = craftService.craft(bobId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        String o2 = craftService.craft(bobId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        craftService.cancel(bobId, o1);              // 取消：释放
        completeWaiting(bobId, o2);                 // 完成：消耗+产出
        String o3 = craftService.craft(bobId, versionId, 1, UUID.randomUUID().toString()).orderNo();
        completeWaiting(bobId, o3);
        craftService.revoke(o3, "ops");              // 撤销：反向

        for (String code : new String[]{mat, out}) {
            long iid = itemId(code);
            // 仅回放本测试产生的单据流水（测试 giveStock 不写 INIT）
            Map<String, Object> replay = jdbc.queryForMap(
                    "SELECT COALESCE(SUM(qty_change),0) t, COALESCE(SUM(held_delta),0) h " +
                            "FROM item_ledger WHERE account_id=? AND item_id=? AND ref_type='ORDER' " +
                            "AND ref_no IN ('" + o1 + "','" + o2 + "','" + o3 + "')",
                    bobId, iid);
            int[] inv = stock(bobId, code);
            int expectedTotal = code.equals(mat) ? 5 + ((Number) replay.get("t")).intValue()
                    : ((Number) replay.get("t")).intValue();
            assertEquals(expectedTotal, inv[0], code + " total = 初始库存 + 单据流水回放");
            assertEquals(((Number) replay.get("h")).intValue(), inv[1], code + " held 必须等于流水回放");
            assertTrue(inv[0] >= 0 && inv[1] >= 0 && inv[1] <= inv[0], code + " 背包约束成立");
        }
    }
}
