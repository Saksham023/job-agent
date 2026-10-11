package io.github.saksham023.jobagent.alerts;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/** telegram_links (user <-> chat), telegram_link_tokens (one-time connect tokens) and telegram_poll_state. */
@Repository
public class TelegramLinkRepository {

    public record Link(long userId, long chatId, boolean active) {
    }

    private final JdbcClient jdbc;

    public TelegramLinkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Link> forUser(long userId) {
        return jdbc.sql("SELECT user_id, chat_id, active FROM telegram_links WHERE user_id = :userId")
                .param("userId", userId)
                .query((rs, n) -> new Link(rs.getLong("user_id"), rs.getLong("chat_id"), rs.getBoolean("active")))
                .optional();
    }

    public Optional<Link> forChat(long chatId) {
        return jdbc.sql("SELECT user_id, chat_id, active FROM telegram_links WHERE chat_id = :chatId")
                .param("chatId", chatId)
                .query((rs, n) -> new Link(rs.getLong("user_id"), rs.getLong("chat_id"), rs.getBoolean("active")))
                .optional();
    }

    /** Links the chat to the user (a chat belongs to one account: an earlier account loses it). */
    public void link(long userId, long chatId) {
        jdbc.sql("DELETE FROM telegram_links WHERE chat_id = :chatId AND user_id <> :userId")
                .param("chatId", chatId).param("userId", userId).update();
        jdbc.sql("""
                        INSERT INTO telegram_links (user_id, chat_id, active, linked_at) VALUES (:userId, :chatId, true, now())
                        ON CONFLICT (user_id) DO UPDATE SET chat_id = EXCLUDED.chat_id, active = true, linked_at = now()
                        """)
                .param("userId", userId).param("chatId", chatId).update();
    }

    public void setActive(long userId, boolean active) {
        jdbc.sql("UPDATE telegram_links SET active = :active WHERE user_id = :userId")
                .param("active", active).param("userId", userId).update();
    }

    public void unlink(long userId) {
        jdbc.sql("DELETE FROM telegram_links WHERE user_id = :userId").param("userId", userId).update();
    }

    // ---------------------------------------------------------------- one-time connect tokens

    public void saveToken(String tokenHash, long userId, Instant expiresAt) {
        jdbc.sql("DELETE FROM telegram_link_tokens WHERE user_id = :userId OR expires_at < now()")
                .param("userId", userId).update();
        jdbc.sql("INSERT INTO telegram_link_tokens (token_hash, user_id, expires_at) VALUES (:hash, :userId, :expiresAt)")
                .param("hash", tokenHash).param("userId", userId).param("expiresAt", Timestamp.from(expiresAt)).update();
    }

    /** The user of a valid token; the token is used up either way (one use only). */
    public Optional<Long> useToken(String tokenHash) {
        return jdbc.sql("DELETE FROM telegram_link_tokens WHERE token_hash = :hash RETURNING user_id, expires_at > now() AS valid")
                .param("hash", tokenHash)
                .query((rs, n) -> rs.getBoolean("valid") ? rs.getLong("user_id") : -1L)
                .optional()
                .filter(userId -> userId >= 0);
    }

    // ---------------------------------------------------------------- reading the bot's messages

    /** The next update this bot has not handled yet; a different bot than the stored one starts from 0. */
    public long nextUpdateId(long botId) {
        jdbc.sql("UPDATE telegram_poll_state SET bot_id = :botId, next_update_id = 0 WHERE id = 1 AND bot_id IS DISTINCT FROM :botId")
                .param("botId", botId).update();
        return jdbc.sql("SELECT next_update_id FROM telegram_poll_state WHERE id = 1").query(Long.class).single();
    }

    public void saveNextUpdateId(long botId, long next) {
        jdbc.sql("UPDATE telegram_poll_state SET next_update_id = :next, bot_id = :botId WHERE id = 1")
                .param("next", next).param("botId", botId).update();
    }
}
