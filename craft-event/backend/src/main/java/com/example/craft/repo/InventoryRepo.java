package com.example.craft.repo;

import com.example.craft.domain.Inventory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 背包仓库。并发合成的正确性全部依赖 SELECT ... FOR UPDATE 行锁：
 * 同一份材料的两笔合成会串行通过这里，第二笔看到的是扣减后的余额。
 */
@Repository
public class InventoryRepo {

    private static final RowMapper<Inventory> MAPPER = (rs, n) -> new Inventory(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getLong("item_id"),
            rs.getInt("total_qty"),
            rs.getInt("held_qty"),
            rs.getTimestamp("updated_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public InventoryRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Inventory> find(Long accountId, Long itemId) {
        return jdbc.query("SELECT * FROM inventory WHERE account_id = ? AND item_id = ?",
                MAPPER, accountId, itemId).stream().findFirst();
    }

    public List<Inventory> findForAccount(Long accountId) {
        return jdbc.query("SELECT * FROM inventory WHERE account_id = ? ORDER BY item_id",
                MAPPER, accountId);
    }

    /** 事务内按主键行锁定；行必须已由初始化播种，缺失视为 0。 */
    public Inventory lockForUpdate(Long accountId, Long itemId) {
        List<Inventory> list = jdbc.query(
                "SELECT * FROM inventory WHERE account_id = ? AND item_id = ? FOR UPDATE",
                MAPPER, accountId, itemId);
        return list.stream().findFirst()
                .orElseGet(() -> new Inventory(null, accountId, itemId, 0, 0, null));
    }

    public void insertIfAbsent(Long accountId, Long itemId, int totalQty) {
        jdbc.update("INSERT IGNORE INTO inventory(account_id, item_id, total_qty, held_qty) VALUES (?,?,?,0)",
                accountId, itemId, totalQty);
    }

    public void applyDelta(Long accountId, Long itemId, int totalDelta, int heldDelta) {
        int rows = jdbc.update(
                "UPDATE inventory SET total_qty = total_qty + ?, held_qty = held_qty + ? " +
                        "WHERE account_id = ? AND item_id = ? " +
                        // 数据库层兜底，任何情况下都不能扣成负数或占用超过总量
                        "AND total_qty + ? >= 0 AND held_qty + ? >= 0 AND held_qty + ? <= total_qty + ?",
                totalDelta, heldDelta, accountId, itemId,
                totalDelta, heldDelta, heldDelta, totalDelta);
        if (rows == 0) {
            throw new IllegalStateException(
                    "inventory guard rejected update account=" + accountId + " item=" + itemId
                            + " totalDelta=" + totalDelta + " heldDelta=" + heldDelta);
        }
    }
}
