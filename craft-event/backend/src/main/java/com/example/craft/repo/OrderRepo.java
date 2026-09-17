package com.example.craft.repo;

import com.example.craft.domain.CraftOrder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class OrderRepo {

    private static final RowMapper<CraftOrder> MAPPER = (rs, n) -> new CraftOrder(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("account_id"),
            rs.getLong("recipe_id"),
            rs.getLong("recipe_version_id"),
            rs.getInt("snapshot_version"),
            rs.getLong("output_item_id"),
            rs.getInt("output_qty"),
            rs.getInt("qty"),
            rs.getString("status"),
            rs.getBoolean("auto_complete"),
            rs.getTimestamp("held_from").toLocalDateTime(),
            rs.getTimestamp("complete_at").toLocalDateTime(),
            rs.getTimestamp("expire_at").toLocalDateTime(),
            ts(rs, "completed_at"),
            ts(rs, "cancelled_at"),
            rs.getString("cancel_reason"),
            ts(rs, "revoked_at"),
            rs.getString("revoke_operator"),
            rs.getString("request_id"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private static LocalDateTime ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private final JdbcTemplate jdbc;

    public OrderRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CraftOrder o) {
        jdbc.update(
                "INSERT INTO craft_order(order_no, account_id, recipe_id, recipe_version_id, snapshot_version, " +
                        "output_item_id, output_qty, qty, status, auto_complete, held_from, complete_at, expire_at, " +
                        "request_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.orderNo(), o.accountId(), o.recipeId(), o.recipeVersionId(), o.snapshotVersion(),
                o.outputItemId(), o.outputQty(), o.qty(), o.status(), o.autoComplete(),
                Timestamp.valueOf(o.heldFrom()), Timestamp.valueOf(o.completeAt()),
                Timestamp.valueOf(o.expireAt()), o.requestId());
    }

    /** 幂等键重放（请求重试）：唯一键 uk_order_idem 保证同一 requestId 只能有一单。 */
    public Optional<CraftOrder> findByIdempotency(Long accountId, String requestId) {
        return jdbc.query("SELECT * FROM craft_order WHERE account_id = ? AND request_id = ?",
                MAPPER, accountId, requestId).stream().findFirst();
    }

    public Optional<CraftOrder> findByOrderNo(String orderNo) {
        return jdbc.query("SELECT * FROM craft_order WHERE order_no = ?", MAPPER, orderNo)
                .stream().findFirst();
    }

    /** 完成/取消/撤销前必须锁住单据行，保证“完成与超时取消”只有一个胜出。 */
    public CraftOrder lockByOrderNo(String orderNo) {
        return jdbc.query("SELECT * FROM craft_order WHERE order_no = ? FOR UPDATE", MAPPER, orderNo)
                .stream().findFirst().orElse(null);
    }

    public List<CraftOrder> findByAccount(Long accountId) {
        return jdbc.query("SELECT * FROM craft_order WHERE account_id = ? ORDER BY id DESC", MAPPER, accountId);
    }

    public List<CraftOrder> findAll() {
        return jdbc.query("SELECT * FROM craft_order ORDER BY id DESC LIMIT 500", MAPPER);
    }

    public int markCompleted(String orderNo, LocalDateTime now) {
        return jdbc.update(
                "UPDATE craft_order SET status='COMPLETED', completed_at=? WHERE order_no=? AND status='HELD'",
                Timestamp.valueOf(now), orderNo);
    }

    public int markCancelled(String orderNo, LocalDateTime now, String reason) {
        return jdbc.update(
                "UPDATE craft_order SET status='CANCELLED', cancelled_at=?, cancel_reason=? " +
                        "WHERE order_no=? AND status='HELD'",
                Timestamp.valueOf(now), reason, orderNo);
    }

    public int markRevoked(String orderNo, LocalDateTime now, String operator) {
        return jdbc.update(
                "UPDATE craft_order SET status='REVOKED', revoked_at=?, revoke_operator=? " +
                        "WHERE order_no=? AND status='COMPLETED'",
                Timestamp.valueOf(now), operator, orderNo);
    }

    /** 自动完成扫描：已到完成时刻、未过超时。逐单独立事务。 */
    public List<CraftOrder> findAutoCompleteDue(LocalDateTime now, int limit) {
        return jdbc.query(
                "SELECT * FROM craft_order WHERE status='HELD' AND auto_complete=1 " +
                        "AND complete_at <= ? AND expire_at > ? ORDER BY complete_at LIMIT ?",
                MAPPER, now, now, limit);
    }

    /** 超时扫描：预占已过 expire_at。 */
    public List<CraftOrder> findExpired(LocalDateTime now, int limit) {
        return jdbc.query(
                "SELECT * FROM craft_order WHERE status='HELD' AND expire_at <= ? ORDER BY expire_at LIMIT ?",
                MAPPER, now, limit);
    }
}
