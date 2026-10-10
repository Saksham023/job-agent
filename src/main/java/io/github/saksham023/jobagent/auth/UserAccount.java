package io.github.saksham023.jobagent.auth;

import java.time.Instant;

/** A stored account. The password hash never leaves the auth package. */
public record UserAccount(long id, String email, String passwordHash, Role role, Instant createdAt, Instant lastLoginAt) {

    public enum Role { USER, ADMIN }

    /** What the API shows about an account. */
    public record View(long id, String email, Role role) {
    }

    public View view() {
        return new View(id, email, role);
    }
}
