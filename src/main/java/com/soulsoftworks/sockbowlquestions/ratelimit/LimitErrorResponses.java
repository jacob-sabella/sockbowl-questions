package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link LimitException} as its HTTP response: status, headers and
 * the {@code application/json} body from plan m4-limits section 2.1. Used by
 * {@link LimitsExceptionAdvice} (controllers) and by the request guard filter,
 * which runs before the dispatcher and so must write the response itself.
 *
 * <p>The body is serialized here, with nulls kept, rather than left to the
 * app's preferred Gson converter, which would drop {@code "resetsAt": null}.
 */
public final class LimitErrorResponses {

    public static final String X_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    public static final String X_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    public static final String X_RATE_LIMIT_POLICY = "X-RateLimit-Policy";

    /** Headers the CORS config must expose so browsers (ng) can read them. */
    public static final List<String> EXPOSED_HEADERS = List.of(
            HttpHeaders.RETRY_AFTER, X_RATE_LIMIT_LIMIT, X_RATE_LIMIT_REMAINING, X_RATE_LIMIT_POLICY);

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    private LimitErrorResponses() {
    }

    public static String json(Map<String, Object> body) {
        return GSON.toJson(body);
    }

    public static ResponseEntity<String> toResponseEntity(LimitException ex) {
        HttpHeaders headers = new HttpHeaders();
        ex.addHeaders(headers);
        return ResponseEntity.status(ex.status())
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(json(ex.body()));
    }

    public static void write(HttpServletResponse response, LimitException ex) throws IOException {
        if (response.isCommitted()) {
            return;
        }
        response.resetBuffer();
        response.setStatus(ex.status().value());
        HttpHeaders headers = new HttpHeaders();
        ex.addHeaders(headers);
        headers.forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(json(ex.body()));
        response.flushBuffer();
    }
}
