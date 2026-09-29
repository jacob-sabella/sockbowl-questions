package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DK-3: the shared prod Redis runs with {@code requirepass}/ACL and no
 * published port, so the limiter's own Lettuce connection
 * ({@link RateLimitRedis}) must authenticate with {@code SOCKBOWL_REDIS_PASSWORD}
 * (wired in {@code application.yml} to {@code spring.data.redis.password},
 * read by {@link RateLimitRedisConfig}). Proven against a real
 * {@code requirepass} Redis, exercising {@link RateLimitRedis} exactly as
 * {@code RateLimitRedisConfig} builds it: a blank or wrong password cannot
 * issue commands (D12: the limiter is meant to fail open on this, never crash
 * the app), and the configured password authenticates and serves commands.
 */
@Testcontainers
class RateLimitRedisAuthIT {

    private static final String PASSWORD = "m7-dk3-secret";

    @Container
    private static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:8.2"))
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", PASSWORD);

    /** Today's no-auth dev/compose default must keep working unchanged (regression guard). */
    @Container
    private static final RedisContainer NO_AUTH_REDIS = new RedisContainer(DockerImageName.parse("redis:8.2"))
            .withExposedPorts(6379);

    private RateLimitRedis client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.destroy();
            client = null;
        }
    }

    private RateLimitRedis redisClient(RedisContainer container, String password) {
        RateLimitProperties.Redis settings = new RateLimitProperties.Redis();
        settings.setTimeout(Duration.ofMillis(500));
        settings.setConnectTimeout(Duration.ofMillis(500));
        settings.setReconnectBackoff(Duration.ofMillis(50));
        client = new RateLimitRedis(container.getHost(), container.getMappedPort(6379), 0, password, settings,
                new RateLimitTimeMeter(Clock.systemUTC()));
        return client;
    }

    @Test
    void blankPasswordCannotIssueCommandsAgainstARequirepassRedis() {
        RateLimitRedis unauthenticated = redisClient(REDIS, "");
        assertThatThrownBy(() -> unauthenticated.sync().ping()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void wrongPasswordCannotIssueCommandsAgainstARequirepassRedis() {
        RateLimitRedis wrongPassword = redisClient(REDIS, "not-the-password");
        assertThatThrownBy(() -> wrongPassword.sync().ping()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void theConfiguredPasswordAuthenticatesAndServesCommands() {
        RateLimitRedis authenticated = redisClient(REDIS, PASSWORD);
        assertThat(authenticated.sync().ping()).isEqualTo("PONG");
    }

    @Test
    void blankPasswordStillWorksAgainstAnUnauthenticatedRedis() {
        RateLimitRedis noAuth = redisClient(NO_AUTH_REDIS, "");
        assertThat(noAuth.sync().ping()).isEqualTo("PONG");
    }
}
