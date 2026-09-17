package com.example.craft.repo;

import com.example.craft.domain.Recipe;
import com.example.craft.domain.RecipeMaterial;
import com.example.craft.domain.RecipeVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class RecipeRepo {

    private static final RowMapper<Recipe> RECIPE_MAPPER = (rs, n) ->
            new Recipe(rs.getLong("id"), rs.getString("code"), rs.getString("name"));

    private static final RowMapper<RecipeVersion> VERSION_MAPPER = (rs, n) -> new RecipeVersion(
            rs.getLong("id"),
            rs.getLong("recipe_id"),
            rs.getInt("version_no"),
            rs.getLong("output_item_id"),
            rs.getInt("output_qty"),
            rs.getTimestamp("event_starts_at").toLocalDateTime(),
            rs.getTimestamp("event_ends_at").toLocalDateTime(),
            rs.getBoolean("auto_complete"),
            rs.getInt("craft_seconds"),
            rs.getInt("timeout_seconds"),
            rs.getString("status"),
            rs.getString("changelog"),
            rs.getString("created_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("published_at") == null ? null : rs.getTimestamp("published_at").toLocalDateTime()
    );

    private static final RowMapper<RecipeMaterial> MATERIAL_MAPPER = (rs, n) -> new RecipeMaterial(
            rs.getLong("id"),
            rs.getLong("recipe_version_id"),
            rs.getLong("item_id"),
            rs.getInt("qty"),
            rs.getInt("seq_no")
    );

    private final JdbcTemplate jdbc;

    public RecipeRepo(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Recipe> findRecipeByCode(String code) {
        return jdbc.query("SELECT * FROM recipe WHERE code = ?", RECIPE_MAPPER, code)
                .stream().findFirst();
    }

    /** 锁配方行，串行化同配方的新版本分配/发布。 */
    public Recipe lockRecipeForUpdate(long recipeId) {
        return jdbc.query("SELECT * FROM recipe WHERE id = ? FOR UPDATE", RECIPE_MAPPER, recipeId)
                .stream().findFirst().orElse(null);
    }

    public List<Recipe> findAllRecipes() {
        return jdbc.query("SELECT * FROM recipe ORDER BY id", RECIPE_MAPPER);
    }

    public long insertRecipe(String code, String name) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recipe(code, name) VALUES (?,?)", Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, code);
            ps.setString(2, name);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public RecipeVersion findVersionById(Long versionId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE id = ?", VERSION_MAPPER, versionId)
                .stream().findFirst().orElse(null);
    }

    public RecipeVersion findPublishedByRecipeId(Long recipeId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_id = ? AND status = 'PUBLISHED' FOR UPDATE",
                VERSION_MAPPER, recipeId).stream().findFirst().orElse(null);
    }

    public RecipeVersion findPublishedByRecipeIdNoLock(Long recipeId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_id = ? AND status = 'PUBLISHED'",
                VERSION_MAPPER, recipeId).stream().findFirst().orElse(null);
    }

    public List<RecipeVersion> findVersions(Long recipeId) {
        return jdbc.query("SELECT * FROM recipe_version WHERE recipe_id = ? ORDER BY version_no",
                VERSION_MAPPER, recipeId);
    }

    public List<RecipeVersion> findAllVersions() {
        return jdbc.query("SELECT * FROM recipe_version ORDER BY recipe_id, version_no", VERSION_MAPPER);
    }

    public long insertDraft(Long recipeId, int versionNo, Long outputItemId, int outputQty,
                            java.time.LocalDateTime startsAt, java.time.LocalDateTime endsAt,
                            boolean autoComplete, int craftSeconds, int timeoutSeconds,
                            String changelog, String operator) {
        KeyHolder kh = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO recipe_version(recipe_id, version_no, output_item_id, output_qty, " +
                            "event_starts_at, event_ends_at, auto_complete, craft_seconds, timeout_seconds, " +
                            "status, changelog, created_by) " +
                            "VALUES (?,?,?,?,?,?,?,?,?,'DRAFT',?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, recipeId);
            ps.setInt(2, versionNo);
            ps.setLong(3, outputItemId);
            ps.setInt(4, outputQty);
            ps.setTimestamp(5, Timestamp.valueOf(startsAt));
            ps.setTimestamp(6, Timestamp.valueOf(endsAt));
            ps.setBoolean(7, autoComplete);
            ps.setInt(8, craftSeconds);
            ps.setInt(9, timeoutSeconds);
            ps.setString(10, changelog);
            ps.setString(11, operator);
            return ps;
        }, kh);
        return kh.getKey().longValue();
    }

    public void addMaterial(Long versionId, Long itemId, int qty, int seqNo) {
        jdbc.update("INSERT INTO recipe_material(recipe_version_id, item_id, qty, seq_no) VALUES (?,?,?,?)",
                versionId, itemId, qty, seqNo);
    }

    /** 发布：旧 PUBLISHED → SUPERSEDED，DRAFT → PUBLISHED。整体在同一事务。 */
    public void publish(Long oldVersionId, Long newVersionId) {
        if (oldVersionId != null) {
            int rows = jdbc.update(
                    "UPDATE recipe_version SET status = 'SUPERSEDED' WHERE id = ? AND status = 'PUBLISHED'",
                    oldVersionId);
            if (rows != 1) {
                throw new IllegalStateException("concurrent recipe publish detected, oldVersion=" + oldVersionId);
            }
        }
        int rows = jdbc.update(
                "UPDATE recipe_version SET status = 'PUBLISHED', published_at = NOW(3) WHERE id = ? AND status = 'DRAFT'",
                newVersionId);
        if (rows != 1) {
            throw new IllegalStateException("draft version missing or already published, version=" + newVersionId);
        }
    }

    public List<RecipeMaterial> findMaterials(Long versionId) {
        return jdbc.query(
                "SELECT * FROM recipe_material WHERE recipe_version_id = ? ORDER BY seq_no",
                MATERIAL_MAPPER, versionId);
    }

    public List<RecipeVersion> findPublishedVersionsForPlayers() {
        return jdbc.query("SELECT * FROM recipe_version WHERE status = 'PUBLISHED' ORDER BY recipe_id",
                VERSION_MAPPER);
    }
}
