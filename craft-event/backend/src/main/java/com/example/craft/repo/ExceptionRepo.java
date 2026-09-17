package com.example.craft.repo;

import com.example.craft.domain.RevokeException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public class ExceptionRepo {

    private static final RowMapper<RevokeException> MAPPER = (rs, n) -> new RevokeException(
            rs.getLong("id"),
            rs.getString("exception_no"),
            rs.getString("order_no"),
            rs.getLong("account_id"),
            rs.getString("reason"),
            (Long) rs.getObject("missing_item_id"),
            (Integer) rs.getObject("missing_qty"),
            rs.getString("status"),
            rs.getString("remark"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toLocalDateTime(),
            rs.getString("resolved_by")
    );

    private final JdbcTemplate jdbc;

    public ExceptionRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String exceptionNo, String orderNo, Long accountId, String reason,
                       Long missingItemId, Integer missingQty, String operator) {
        jdbc.update(
                "INSERT INTO revoke_exception(exception_no, order_no, account_id, reason, missing_item_id, " +
                        "missing_qty, created_by) VALUES (?,?,?,?,?,?,?)",
                exceptionNo, orderNo, accountId, reason, missingItemId, missingQty, operator);
    }

    public List<RevokeException> findOpen() {
        return jdbc.query("SELECT * FROM revoke_exception WHERE status='OPEN' ORDER BY id", MAPPER);
    }

    public List<RevokeException> findAll() {
        return jdbc.query("SELECT * FROM revoke_exception ORDER BY id DESC LIMIT 500", MAPPER);
    }

    public int resolve(String exceptionNo, String operator, String remark, LocalDateTime now) {
        return jdbc.update(
                "UPDATE revoke_exception SET status='RESOLVED', resolved_at=?, resolved_by=?, remark=? " +
                        "WHERE exception_no=? AND status='OPEN'",
                Timestamp.valueOf(now), operator, remark, exceptionNo);
    }
}
