package io.github.saksham023.jobagent.account;

import io.github.saksham023.jobagent.requirements.JobFamily;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/** user_profiles: one row per user who has a profile. */
@Repository
public class UserProfileRepository {

    /** A stored row (without the computed search range). */
    public record Row(UserProfile.Facts facts, String driveLink, String source, Instant readAt, Instant editedAt) {
    }

    private final JdbcClient jdbc;

    public UserProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Row> find(long userId) {
        return jdbc.sql("""
                        SELECT headline, build, years, main_languages, skills, roles_wanted, families, drive_link, source, read_at, edited_at
                        FROM user_profiles WHERE user_id = :userId
                        """)
                .param("userId", userId)
                .query(UserProfileRepository::map)
                .optional();
    }

    /** A freshly read resume replaces the facts; the link is the new one (null for an upload keeps none). */
    public void saveRead(long userId, UserProfile.Facts facts, String source, String driveLink) {
        jdbc.sql("""
                        INSERT INTO user_profiles (user_id, headline, build, years, main_languages, skills, roles_wanted, families, drive_link,
                                                   source, read_at, edited_at, updated_at)
                        VALUES (:userId, :headline, :build, :years, :languages, :skills, :roles, :families, :link, :source, now(), NULL, now())
                        ON CONFLICT (user_id) DO UPDATE SET
                            headline = EXCLUDED.headline, build = EXCLUDED.build, years = EXCLUDED.years, main_languages = EXCLUDED.main_languages, skills = EXCLUDED.skills,
                            roles_wanted = EXCLUDED.roles_wanted, families = EXCLUDED.families,
                            drive_link = coalesce(EXCLUDED.drive_link, user_profiles.drive_link),
                            source = EXCLUDED.source, read_at = now(), edited_at = NULL, updated_at = now()
                        """)
                .param("userId", userId)
                .param("headline", facts.headline())
                .param("build", facts.build())
                .param("years", facts.years())
                .param("languages", facts.mainLanguages().toArray(String[]::new))
                .param("skills", facts.skills().toArray(String[]::new))
                .param("roles", facts.rolesWanted())
                .param("families", facts.families().stream().map(Enum::name).toArray(String[]::new))
                .param("link", driveLink)
                .param("source", source)
                .update();
    }

    /** The user's own edits (a user without a profile yet gets one, source "manual"). */
    public void saveEdit(long userId, UserProfile.Facts facts, String driveLink) {
        jdbc.sql("""
                        INSERT INTO user_profiles (user_id, headline, build, years, main_languages, skills, roles_wanted, families, drive_link,
                                                   source, edited_at, updated_at)
                        VALUES (:userId, :headline, :build, :years, :languages, :skills, :roles, :families, :link, 'manual', now(), now())
                        ON CONFLICT (user_id) DO UPDATE SET
                            headline = EXCLUDED.headline, build = EXCLUDED.build, years = EXCLUDED.years, main_languages = EXCLUDED.main_languages, skills = EXCLUDED.skills,
                            roles_wanted = EXCLUDED.roles_wanted, families = EXCLUDED.families, drive_link = EXCLUDED.drive_link,
                            edited_at = now(), updated_at = now()
                        """)
                .param("userId", userId)
                .param("headline", facts.headline())
                .param("build", facts.build())
                .param("years", facts.years())
                .param("languages", facts.mainLanguages().toArray(String[]::new))
                .param("skills", facts.skills().toArray(String[]::new))
                .param("roles", facts.rolesWanted())
                .param("families", facts.families().stream().map(Enum::name).toArray(String[]::new))
                .param("link", driveLink)
                .update();
    }

    private static Row map(ResultSet rs, int rowNum) throws SQLException {
        BigDecimal years = rs.getBigDecimal("years");
        List<JobFamily> families = list(rs.getArray("families")).stream().map(JobFamily::valueOf).toList();
        UserProfile.Facts facts = new UserProfile.Facts(rs.getString("headline"), rs.getString("build"), years == null ? null : years.doubleValue(),
                list(rs.getArray("main_languages")), list(rs.getArray("skills")), rs.getString("roles_wanted"), families);
        return new Row(facts, rs.getString("drive_link"), rs.getString("source"), instant(rs.getTimestamp("read_at")),
                instant(rs.getTimestamp("edited_at")));
    }

    private static List<String> list(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
