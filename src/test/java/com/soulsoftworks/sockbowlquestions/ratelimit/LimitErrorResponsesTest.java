package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/** The HTTP error bodies and headers of plan m4-limits section 2.1. */
class LimitErrorResponsesTest {

    private final LimitsExceptionAdvice advice = new LimitsExceptionAdvice();

    @Test
    void rateLimited() throws Exception {
        RateLimitExceededException ex = RateLimitExceededException.from("session-create",
                Decision.rejected(36_200_000_000L, 3));

        ResponseEntity<String> entity = advice.handleLimitException(ex);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(entity.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(entity.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("37");
        assertThat(entity.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("3");
        assertThat(entity.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
        assertThat(entity.getHeaders().getFirst("X-RateLimit-Policy")).isEqualTo("session-create");
        assertThat(entity.getBody()).isEqualTo(
                "{\"error\":\"rate_limited\",\"policy\":\"session-create\",\"retryAfterSeconds\":37,"
                        + "\"message\":\"Too many requests\"}");

        MockHttpServletResponse response = new MockHttpServletResponse();
        LimitErrorResponses.write(response, ex);
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).startsWith("application/json");
        assertThat(response.getHeader("Retry-After")).isEqualTo("37");
        assertThat(response.getHeader("X-RateLimit-Policy")).isEqualTo("session-create");
        assertThat(response.getContentAsString()).isEqualTo(entity.getBody());
    }

    @Test
    void quotaExceededKeepsANullResetsAt() {
        QuotaExceededException concurrent = new QuotaExceededException("hosted-sessions", 2, 2, null);
        assertThat(advice.handleLimitException(concurrent).getBody()).isEqualTo(
                "{\"error\":\"quota_exceeded\",\"metric\":\"hosted-sessions\",\"limit\":2,\"used\":2,\"resetsAt\":null}");
        assertThat(advice.handleLimitException(concurrent).getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();

        QuotaExceededException daily = new QuotaExceededException("ai.generations", 20, 20,
                Instant.parse("2026-09-28T00:00:00Z"), Instant.parse("2026-09-27T23:00:00Z"));
        ResponseEntity<String> entity = advice.handleLimitException(daily);
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(entity.getBody()).isEqualTo(
                "{\"error\":\"quota_exceeded\",\"metric\":\"ai.generations\",\"limit\":20,\"used\":20,"
                        + "\"resetsAt\":\"2026-09-28T00:00:00Z\"}");
        assertThat(entity.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("3600");
    }

    @Test
    void limiterUnavailable() {
        ResponseEntity<String> entity = advice.handleLimitException(new LimiterUnavailableException("ai-generate"));
        assertThat(entity.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(entity.getBody()).isEqualTo("{\"error\":\"limiter_unavailable\",\"policy\":\"ai-generate\"}");
    }

    @Test
    void bans() {
        ResponseEntity<String> subject = advice.handleLimitException(
                new SubjectBannedException("spam <b>", null));
        assertThat(subject.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(subject.getBody()).isEqualTo("{\"error\":\"banned\",\"reason\":\"spam <b>\",\"expiresAt\":null}");

        ResponseEntity<String> ip = advice.handleLimitException(
                new IpBannedException(Instant.parse("2026-10-01T00:00:00Z")));
        assertThat(ip.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(ip.getBody()).isEqualTo("{\"error\":\"ip_banned\",\"expiresAt\":\"2026-10-01T00:00:00Z\"}");
    }

    @Test
    void retryAfterSecondsRoundsUpAndIsAtLeastOne() {
        assertThat(Decision.rejected(1, 5).retryAfterSeconds()).isEqualTo(1);
        assertThat(Decision.rejected(0, 5).retryAfterSeconds()).isEqualTo(1);
        assertThat(Decision.rejected(2_000_000_000L, 5).retryAfterSeconds()).isEqualTo(2);
        assertThat(Decision.rejected(2_000_000_001L, 5).retryAfterSeconds()).isEqualTo(3);
        assertThat(Decision.allowed(4, 5).retryAfterSeconds()).isZero();
    }

    @Test
    void exposedHeadersListCoversEveryLimitHeader() {
        assertThat(LimitErrorResponses.EXPOSED_HEADERS)
                .containsExactly("Retry-After", "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Policy");
    }
}
