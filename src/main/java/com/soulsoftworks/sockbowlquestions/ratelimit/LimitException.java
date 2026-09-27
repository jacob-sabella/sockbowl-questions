package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

import java.util.Map;

/**
 * Base of every limit/quota/ban rejection. Each subclass knows its HTTP status,
 * its JSON body (plan m4-limits section 2.1) and any extra headers, so the
 * controller advice and the servlet filter render it identically through
 * {@link LimitErrorResponses}.
 */
public abstract class LimitException extends RuntimeException {

    protected LimitException(String message) {
        super(message);
    }

    public abstract HttpStatus status();

    /** Ordered JSON body; {@code null} values are written as JSON {@code null}. */
    public abstract Map<String, Object> body();

    public void addHeaders(HttpHeaders headers) {
        // none by default
    }
}
