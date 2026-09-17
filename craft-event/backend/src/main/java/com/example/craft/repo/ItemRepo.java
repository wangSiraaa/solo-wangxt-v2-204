package com.example.craft.repo;

import com.example.craft.domain.Item;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class ItemRepo {

    private static final RowMapper<Item> MAPPER = (rs, n) ->
            new Item(rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private final JdbcTemplate jdbc;

    public ItemRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Item> findByCode(String code) {
        return jdbc.query("SELECT * FROM item WHERE code = ?", MAPPER, code).stream().findFirst();
    }

    public List<Item> findAll() {
        return jdbc.query("SELECT * FROM item ORDER BY id", MAPPER);
    }

    public void insert(String code, String name) {
        jdbc.update("INSERT INTO item(code, name) VALUES (?,?)", code, name);
    }
}
