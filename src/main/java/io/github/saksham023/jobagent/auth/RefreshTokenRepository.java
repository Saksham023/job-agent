package io.github.saksham023.jobagent.auth;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** refresh_tokens: one row per issued refresh token, found by the SHA-256 hash of the token the browser holds. */
@Repository
public class RefreshTokenRepository {

    /** A stored refresh token. revokedAt is set when it was used (replaced) or ended by a logout. */
    public record Stored(long id, long userId, UUID family, Instant expiresAt, Instant revokedAt) {
    }

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new row's id */
    public long insert(long userId, String tokenHash, UUID family, Instant expiresAt) {
        return jdbc.sql("""
                        INSERT INTO refresh_tokens (user_id, token_hash, family, expires_at)
                        VALUES (:userId, :hash, :family, :expiresAt) RETURNING id
                        """)
                .param("userId", userId)
                .param("hash", tokenHash)
                .param("family", family)
                .param("expiresAt", Timestamp.from(expiresAt))
                .query(Long.class)
                .single();
    }

    public Optional<Stored> find(String tokenHash) {
        return jdbc.sql("SELECT id, user_id, family, expires_at, revoked_at FROM refresh_tokens WHERE token_hash = :hash")
                .param("hash", tokenHash)
                .query((rs, n) -> new Stored(rs.getLong("id"), rs.getLong("user_id"), rs.getObject("family", UUID.class),
                        rs.getTimestamp("expires_at").toInstant(),
                        rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()))
                .optional();
    }

    /**
     * Marks a token as used, but only if nobody used it first (two refreshes at once with the same token: only one wins).
     *
     * @return true when this call took it
     */
    public boolean claim(long id) {
        return jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE id = :id AND revoked_at IS NULL")
                .param("id", id)
                .update() == 1;
    }

    public void linkReplacement(long id, long replacedBy) {
        jdbc.sql("UPDATE refresh_tokens SET replaced_by = :next WHERE id = :id").param("next", replacedBy).param("id", id).update();
    }

    public void revokeFamily(UUID family) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE family = :family AND revoked_at IS NULL")
                .param("family", family).update();
    }

    public void revokeAllForUser(long userId) {
        jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
                .param("userId", userId).update();
    }

    /** Housekeeping: tokens that expired more than a day ago are of no use any more. */
    public int deleteExpired() {
        return jdbc.sql("DELETE FROM refresh_tokens WHERE expires_at < now() - interval '1 day'").update();
    }
}
