package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global exception handler for REST API endpoints.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Handle invalid API request exceptions.
     *
     * @param ex The exception
     * @return 400 Bad Request response with error message
     */
    @ExceptionHandler(InvalidApiRequestException.class)
    public ResponseEntity<String> handleInvalidApiRequest(InvalidApiRequestException ex) {
        logger.warn("Invalid API request: {}", ex.getMessage());
        return ResponseEntity.badRequest().body(ex.getMessage());
    }
}
