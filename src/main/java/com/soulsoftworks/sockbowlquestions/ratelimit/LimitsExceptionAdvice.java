package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps every {@link LimitException} thrown from a controller or service to its
 * JSON response (plan m4-limits section 2.1). A separate, highest-precedence
 * advice so {@code GlobalExceptionHandler} stays untouched.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LimitsExceptionAdvice {

    @ExceptionHandler(LimitException.class)
    public ResponseEntity<String> handleLimitException(LimitException ex) {
        return LimitErrorResponses.toResponseEntity(ex);
    }
}
