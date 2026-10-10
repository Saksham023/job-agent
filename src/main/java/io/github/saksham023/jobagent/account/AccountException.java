package io.github.saksham023.jobagent.account;

import org.springframework.http.HttpStatus;

/** A problem with the user's resume or profile that the user should read as is, with its HTTP status. */
public class AccountException extends RuntimeException {

    private final HttpStatus status;

    public AccountException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
