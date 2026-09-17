package com.example.craft.repo;

import com.example.craft.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;

/**
 * 只追加流水：任何修改都通过新插入一笔表达。
 * 余额只信回放：invariant: Σqty_change = total_qty, Σheld_delta = held_qty。
 */
@Repository
public class LedgerRepo {

    private static final RowMapper<LedgerEntry> MAPPER = (rs, n) -> new LedgerEntry(
            rs.getLong("id"),
            rs.getLong("account_id"),
            rs.getLong("item_id"),
            rs.getString("change_type"),
            rs.getInt("qty_change"),
            rs.getInt("held_delta"),
            rs.getInt("balance_total"),
            rs.getInt("balance_held"),
            rs.getString("ref_type"),
            rs.getString("ref_no"),
            (Long) rs.getObject("reversal_of"),
            rs.getString("remark"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public LedgerRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一笔并返回新流水 id。balance_total / balance_held 必须由调用方在行锁内算好，
     * 不允许在 SQL 里读旧值，避免“算与写”之间被穿插。
     */
    public long append(Long accountId, Long itemId, String type, int qtyChange, int heldDelta,
                       int balanceTotal, int balanceHeld, String refType, String refNo,
                       Long reversalOf, String remark) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO item_ledger(account_id, item_id, change_type, qty_change, held_delta, " +
                            "balance_total, balance_held, ref_type, ref_no, reversal_of, remark) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, accountId);
            ps.setLong(2, itemId);
            ps.setString(3, type);
            ps.setInt(4, qtyChange);
            ps.setInt(5, heldDelta);
            ps.setInt(6, balanceTotal);
            ps.setInt(7, balanceHeld);
            ps.setString(8, refType);
            ps.setString(9, refNo);
            if (reversalOf == null) {
                ps.setNull(10, java.sql.Types.BIGINT);
            } else {
                ps.setLong(10, reversalOf);
            }
            ps.setString(11, remark);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public List<LedgerEntry> findByAccount(Long accountId) {
        return jdbc.query(
                "SELECT * FROM item_ledger WHERE account_id = ? ORDER BY id DESC LIMIT 1000", MAPPER, accountId);
    }

    public List<LedgerEntry> findByRef(String refType, String refNo) {
        return jdbc.query("SELECT * FROM item_ledger WHERE ref_type = ? AND ref_no = ? ORDER BY id",
                MAPPER, refType, refNo);
    }

    /** 取某玩家某道具的原生产出流水（撤销时做冲正配对）。 */
    public LedgerEntry findProduceEntry(String orderNo, Long accountId, Long itemId) {
        return jdbc.query(
                "SELECT * FROM item_ledger WHERE ref_type='ORDER' AND ref_no=? AND account_id=? AND item_id=? " +
                        "AND change_type='PRODUCE' ORDER BY id LIMIT 1",
                MAPPER, orderNo, accountId, itemId).stream().findFirst().orElse(null);
    }
}
