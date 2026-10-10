package io.github.saksham023.jobagent.auth;

import org.springframework.http.HttpStatus;

/** A sign-in problem the client should see as is, with its HTTP status (401, 403, 409...). */
public class AuthException extends RuntimeException {

    private final HttpStatus status;

    public AuthException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
