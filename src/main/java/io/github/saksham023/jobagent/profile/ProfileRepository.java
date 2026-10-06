package io.github.saksham023.jobagent.profile;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** The profiles table. Facts are written once; only the usage columns and the search defaults change later. */
@Repository
public class ProfileRepository {

    private static final String COLUMNS = """
            id, facts::text AS facts, source, defaults::text AS defaults, created_at, last_used_at, last_search_at
            """;

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;

    public ProfileRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    /** Inserts a profile under this id; false when the id is taken already (then the caller draws another). */
    boolean insert(String id, ProfileFacts facts, String source) {
        return jdbc.sql("""
                        INSERT INTO profiles (id, facts, source) VALUES (:id, CAST(:facts AS jsonb), :source)
                        ON CONFLICT (id) DO NOTHING
                        """)
                .param("id", id)
                .param("facts", jsonMapper.writeValueAsString(facts))
                .param("source", source)
                .update() == 1;
    }

    public Optional<SavedProfile> find(String id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM profiles WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional();
    }

    /** After a search with this profile: its preferences become the defaults, and the judgments key is recorded. */
    public void recordSearch(String id, SearchPreferences used, String profileHash) {
        jdbc.sql("""
                        UPDATE profiles
                        SET defaults = CAST(:defaults AS jsonb), profile_hash = :profileHash,
                            last_search_at = now(), last_used_at = now()
                        WHERE id = :id
                        """)
                .param("id", id)
                .param("defaults", jsonMapper.writeValueAsString(used))
                .param("profileHash", profileHash)
                .update();
    }

    /** Any other use (more jobs, export, get_profile). */
    public void touch(String id) {
        jdbc.sql("UPDATE profiles SET last_used_at = now() WHERE id = :id").param("id", id).update();
    }

    private SavedProfile map(ResultSet rs, int rowNum) throws SQLException {
        String defaults = rs.getString("defaults");
        return new SavedProfile(rs.getString("id"),
                jsonMapper.readValue(rs.getString("facts"), ProfileFacts.class),
                rs.getString("source"),
                defaults == null ? null : jsonMapper.readValue(defaults, SearchPreferences.class),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("last_used_at")),
                instant(rs.getTimestamp("last_search_at")));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
