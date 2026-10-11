package io.github.saksham023.jobagent.alerts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** alert_prefs: one row per user who saved a job alert. */
@Repository
public class AlertPrefsRepository {

    private final JdbcClient jdbc;

    public AlertPrefsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<AlertPrefs> find(long userId) {
        return jdbc.sql("""
                        SELECT enabled, families, min_years, max_years, include_unstated, skills, cities, companies
                        FROM alert_prefs WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(AlertPrefsRepository::map)
                .optional();
    }

    public void save(long userId, AlertPrefs prefs) {
        jdbc.sql("""
                        INSERT INTO alert_prefs (user_id, enabled, families, min_years, max_years, include_unstated, skills,
                                                 cities, companies, updated_at)
                        VALUES (:userId, :enabled, :families, :minYears, :maxYears, :includeUnstated, :skills, :cities,
                                :companies, now())
                        ON CONFLICT (user_id) DO UPDATE SET
                            enabled = EXCLUDED.enabled, families = EXCLUDED.families, min_years = EXCLUDED.min_years,
                            max_years = EXCLUDED.max_years, include_unstated = EXCLUDED.include_unstated,
                            skills = EXCLUDED.skills, cities = EXCLUDED.cities, companies = EXCLUDED.companies,
                            updated_at = now()
                        """)
                .param("userId", userId)
                .param("enabled", prefs.enabled())
                .param("families", prefs.families().toArray(String[]::new))
                .param("minYears", prefs.minYears())
                .param("maxYears", prefs.maxYears())
                .param("includeUnstated", prefs.includeUnstated())
                .param("skills", prefs.skills().toArray(String[]::new))
                .param("cities", prefs.cities().toArray(String[]::new))
                .param("companies", prefs.companies().toArray(String[]::new))
                .update();
    }

    /** The slugs among these that are real, enabled companies. */
    public List<String> knownCompanies(List<String> slugs) {
        return jdbc.sql("SELECT slug FROM companies WHERE enabled AND slug = ANY(:slugs)")
                .param("slugs", slugs.toArray(String[]::new))
                .query(String.class).list();
    }

    private static AlertPrefs map(ResultSet rs, int rowNum) throws SQLException {
        return new AlertPrefs(rs.getBoolean("enabled"), list(rs.getArray("families")), rs.getObject("min_years", Integer.class),
                rs.getObject("max_years", Integer.class), rs.getBoolean("include_unstated"), list(rs.getArray("skills")),
                list(rs.getArray("cities")), list(rs.getArray("companies")));
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }
}
