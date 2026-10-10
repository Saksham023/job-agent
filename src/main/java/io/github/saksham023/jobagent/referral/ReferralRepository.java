package io.github.saksham023.jobagent.referral;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** referral_templates: a row only for users who rewrote the message. */
@Repository
public class ReferralRepository {

    private final JdbcClient jdbc;

    public ReferralRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> find(long userId) {
        return jdbc.sql("SELECT text FROM referral_templates WHERE user_id = :userId")
                .param("userId", userId)
                .query(String.class)
                .optional();
    }

    public void save(long userId, String text) {
        jdbc.sql("""
                        INSERT INTO referral_templates (user_id, text, updated_at) VALUES (:userId, :text, now())
                        ON CONFLICT (user_id) DO UPDATE SET text = EXCLUDED.text, updated_at = now()
                        """)
                .param("userId", userId)
                .param("text", text)
                .update();
    }

    public void delete(long userId) {
        jdbc.sql("DELETE FROM referral_templates WHERE user_id = :userId").param("userId", userId).update();
    }
}
