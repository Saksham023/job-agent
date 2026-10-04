package io.github.saksham023.jobagent.company;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the `companies` table (the crawl registry).
 * Uses plain SQL via JdbcClient and a hand-written row mapper (needed for the jsonb and timestamptz columns).
 */
@Repository
public class CompanyRepository {

    private static final String SELECT_COMPANY = """
            SELECT id, slug, name, platform, config, careers_url, enabled, notes, created_at, updated_at
            FROM companies
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public CompanyRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public List<Company> findAll() {
        return jdbc.sql(SELECT_COMPANY + "ORDER BY slug")
                .query(this::mapRow)
                .list();
    }

    public List<Company> findEnabledByPlatform(String platform) {
        return jdbc.sql(SELECT_COMPANY + "WHERE enabled AND platform = :platform ORDER BY slug")
                .param("platform", platform)
                .query(this::mapRow)
                .list();
    }

    public Optional<Company> findBySlug(String slug) {
        return jdbc.sql(SELECT_COMPANY + "WHERE slug = :slug")
                .param("slug", slug)
                .query(this::mapRow)
                .optional();
    }

    private Company mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new Company(
                rs.getLong("id"),
                rs.getString("slug"),
                rs.getString("name"),
                rs.getString("platform"),
                readJson(rs.getString("config")),
                rs.getString("careers_url"),
                rs.getBoolean("enabled"),
                rs.getString("notes"),
                toInstant(rs.getObject("created_at", OffsetDateTime.class)),
                toInstant(rs.getObject("updated_at", OffsetDateTime.class))
        );
    }

    private JsonNode readJson(String json) {
        return jsonMapper.readTree(json);
    }

    private static Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}