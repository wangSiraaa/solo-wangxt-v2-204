package com.example.craft.repo;

import com.example.craft.domain.MaterialHold;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class HoldRepo {

    private static final RowMapper<MaterialHold> MAPPER = (rs, n) -> new MaterialHold(
            rs.getLong("id"),
            rs.getString("order_no"),
            rs.getLong("account_id"),
            rs.getLong("item_id"),
            rs.getInt("qty"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            ts(rs, "consumed_at"),
            ts(rs, "released_at"),
            ts(rs, "reversed_at")
    );

    private static LocalDateTime ts(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        Timestamp t = rs.getTimestamp(col);
        return t == null ? null : t.toLocalDateTime();
    }

    private final JdbcTemplate jdbc;

    public HoldRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String orderNo, Long accountId, Long itemId, int qty) {
        jdbc.update(
                "INSERT INTO material_hold(order_no, account_id, item_id, qty, status) VALUES (?,?,?,?,'HELD')",
                orderNo, accountId, itemId, qty);
    }

    public List<MaterialHold> findByOrderNoForUpdate(String orderNo) {
        return jdbc.query("SELECT * FROM material_hold WHERE order_no = ? FOR UPDATE", MAPPER, orderNo);
    }

    public List<MaterialHold> findByOrderNoReadOnly(String orderNo) {
        return jdbc.query("SELECT * FROM material_hold WHERE order_no = ? ORDER BY id", MAPPER, orderNo);
    }

    public List<MaterialHold> findByAccount(Long accountId) {
        return jdbc.query(
                "SELECT * FROM material_hold WHERE account_id = ? ORDER BY id DESC LIMIT 500", MAPPER, accountId);
    }

    public int consume(long holdId, LocalDateTime now) {
        return jdbc.update("UPDATE material_hold SET status='CONSUMED', consumed_at=? WHERE id=? AND status='HELD'",
                Timestamp.valueOf(now), holdId);
    }

    public int release(long holdId, LocalDateTime now) {
        return jdbc.update("UPDATE material_hold SET status='RELEASED', released_at=? WHERE id=? AND status='HELD'",
                Timestamp.valueOf(now), holdId);
    }

    public int reverse(long holdId, LocalDateTime now) {
        return jdbc.update("UPDATE material_hold SET status='REVERSED', reversed_at=? WHERE id=? AND status='CONSUMED'",
                Timestamp.valueOf(now), holdId);
    }
}
