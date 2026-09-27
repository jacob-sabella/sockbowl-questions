package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Redis key contract shared with sockbowl-questions (plan m4-limits
 * section 2.1). These golden strings are duplicated verbatim in questions'
 * {@code UsageKeysContractTest}; changing a key here without changing it there
 * (and in every reader) breaks the other service silently, so both builds pin it.
 */
class UsageKeysContractTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 27);

    @Test
    void rateLimitBucketKeys() {
        assertThat(UsageKeys.RL_PREFIX).isEqualTo("rl");
        assertThat(UsageKeys.userPart("abc-123")).isEqualTo("u:abc-123");
        assertThat(UsageKeys.ipPart("192.0.2.1")).isEqualTo("ip:192.0.2.1");
        assertThat(UsageKeys.ipPart("2001:db8::/64")).isEqualTo("ip:2001:db8::/64");
        assertThat(UsageKeys.connectionPart("sess-1")).isEqualTo("conn:sess-1");
        assertThat(UsageKeys.rateLimit("session-create", UsageKeys.userPart("abc-123")))
                .isEqualTo("rl:session-create:u:abc-123");
        assertThat(UsageKeys.rateLimit("session-join", UsageKeys.ipPart("192.0.2.1")))
                .isEqualTo("rl:session-join:ip:192.0.2.1");
        assertThat(UsageKeys.rateLimit("x", "default", "u:s")).isEqualTo("x:default:u:s");
    }

    @Test
    void eventStreamKeys() {
        assertThat(UsageKeys.events()).isEqualTo("rl:events");
        assertThat(UsageKeys.eventSample("ai-generate", "u:abc")).isEqualTo("rl:evsample:ai-generate:u:abc");
    }

    @Test
    void usageAndQuotaKeys() {
        assertThat(UsageKeys.day(DAY)).isEqualTo("20260927");
        assertThat(UsageKeys.day(Instant.parse("2026-09-27T23:59:59Z"))).isEqualTo("20260927");
        assertThat(UsageKeys.day(Instant.parse("2026-09-28T00:00:00Z"))).isEqualTo("20260928");
        assertThat(UsageKeys.daily("abc", "ai.generations", DAY)).isEqualTo("usage:abc:ai.generations:d:20260927");
        assertThat(UsageKeys.daily("abc", "imports", DAY)).isEqualTo("usage:abc:imports:d:20260927");
        assertThat(UsageKeys.globalDaily("ai.serverkey", DAY)).isEqualTo("usage:global:ai.serverkey:d:20260927");
        assertThat(UsageKeys.sessionOwnerUser("abc")).isEqualTo("u:abc");
        assertThat(UsageKeys.sessionOwnerIp("192.0.2.1")).isEqualTo("ip:192.0.2.1");
        assertThat(UsageKeys.sessions("u:abc")).isEqualTo("usage:u:abc:sessions");
        assertThat(UsageKeys.sessions("ip:192.0.2.1")).isEqualTo("usage:ip:192.0.2.1:sessions");
        assertThat(UsageKeys.meta("abc")).isEqualTo("usage:abc:meta");
        assertThat(UsageKeys.ips("abc")).isEqualTo("usage:abc:ips");
        assertThat(UsageKeys.quotaOverride("abc")).isEqualTo("quota:override:abc");
    }

    @Test
    void banAndAiKeys() {
        assertThat(UsageKeys.ban("abc")).isEqualTo("ban:abc");
        assertThat(UsageKeys.ipBans()).isEqualTo("ipban:all");
        assertThat(UsageKeys.aiInflight("abc")).isEqualTo("ai:inflight:abc");
    }

    @Test
    void metricNames() {
        assertThat(UsageKeys.HOSTED_SESSIONS).isEqualTo("hosted-sessions");
        assertThat(UsageKeys.AI_GENERATIONS).isEqualTo("ai.generations");
        assertThat(UsageKeys.AI_QUESTIONS).isEqualTo("ai.questions");
        assertThat(UsageKeys.AI_TOKENS).isEqualTo("ai.tokens");
        assertThat(UsageKeys.IMPORTS).isEqualTo("imports");
        assertThat(UsageKeys.PACKETS_OWNED).isEqualTo("packets-owned");
        assertThat(UsageKeys.AI_SERVERKEY).isEqualTo("ai.serverkey");
    }
}
