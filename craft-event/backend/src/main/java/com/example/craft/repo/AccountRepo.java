package com.example.craft.repo;

import com.example.craft.domain.Account;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public class AccountRepo {

    private static final RowMapper<Account> MAPPER = (rs, n) -> new Account(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("role"),
            rs.getBoolean("enabled"),
            rs.getTimestamp("created_at").toLocalDateTime()
    );

    private final JdbcTemplate jdbc;

    public AccountRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findByUsername(String username) {
        return jdbc.query("SELECT * FROM account WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public Optional<Account> findById(Long id) {
        return jdbc.query("SELECT * FROM account WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public List<Account> findAll() {
        return jdbc.query("SELECT * FROM account ORDER BY id", MAPPER);
    }

    public long count() {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM account", Long.class);
        return c == null ? 0 : c;
    }

    public void insert(String username, String passwordHash, String displayName, String role) {
        jdbc.update("INSERT INTO account(username, password_hash, display_name, role) VALUES (?,?,?,?)",
                username, passwordHash, displayName, role);
    }

    // ---- 登录令牌 ----

    public String createToken(Long accountId, LocalDateTime expiresAt) {
        String token = java.util.UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO login_token(token, account_id, expires_at) VALUES (?,?,?)",
                token, accountId, Timestamp.valueOf(expiresAt));
        return token;
    }

    /** 令牌换取账号；不存在/过期返回空。重试请求天然安全：不做任何计数/状态变更。 */
    public Optional<Account> resolveToken(String token) {
        List<Account> list = jdbc.query(
                "SELECT a.* FROM account a JOIN login_token t ON t.account_id = a.id " +
                        "WHERE t.token = ? AND t.expires_at > NOW(3) AND a.enabled = 1",
                MAPPER, token);
        return list.stream().findFirst();
    }

    public int deleteToken(String token) {
        return jdbc.update("DELETE FROM login_token WHERE token = ?", token);
    }
}
