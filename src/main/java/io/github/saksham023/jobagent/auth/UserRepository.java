package io.github.saksham023.jobagent.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;

/** users: the accounts. */
@Repository
public class UserRepository {

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** @return the new account, or empty when the email is taken */
    public Optional<UserAccount> create(String email, String passwordHash) {
        try {
            return Optional.of(jdbc.sql("""
                            INSERT INTO users (email, password_hash) VALUES (:email, :hash)
                            RETURNING id, email, password_hash, role, created_at, last_login_at
                            """)
                    .param("email", email)
                    .param("hash", passwordHash)
                    .query(UserRepository::map)
                    .single());
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
    }

    public Optional<UserAccount> findByEmail(String email) {
        return jdbc.sql("SELECT id, email, password_hash, role, created_at, last_login_at FROM users WHERE email = :email")
                .param("email", email)
                .query(UserRepository::map)
                .optional();
    }

    public Optional<UserAccount> findById(long id) {
        return jdbc.sql("SELECT id, email, password_hash, role, created_at, last_login_at FROM users WHERE id = :id")
                .param("id", id)
                .query(UserRepository::map)
                .optional();
    }

    public void touchLogin(long id) {
        jdbc.sql("UPDATE users SET last_login_at = now() WHERE id = :id").param("id", id).update();
    }

    private static UserAccount map(ResultSet rs, int rowNum) throws SQLException {
        Timestamp last = rs.getTimestamp("last_login_at");
        return new UserAccount(rs.getLong("id"), rs.getString("email"), rs.getString("password_hash"),
                UserAccount.Role.valueOf(rs.getString("role")), rs.getTimestamp("created_at").toInstant(),
                last == null ? null : last.toInstant());
    }
}
