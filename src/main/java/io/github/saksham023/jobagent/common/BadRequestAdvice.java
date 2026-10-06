package io.github.saksham023.jobagent.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The admin endpoints throw IllegalArgumentException for a bad request (unknown id, wrong combination of
 * parameters); answer it as 400 with the message, instead of a bare 500.
 */
@RestControllerAdvice
public class BadRequestAdvice {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
