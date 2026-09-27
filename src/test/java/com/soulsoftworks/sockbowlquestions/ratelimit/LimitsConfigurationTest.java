package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.soulsoftworks.sockbowlquestions.quota.QuotaProperties;
import com.soulsoftworks.sockbowlquestions.quota.QuotaService;
import com.soulsoftworks.sockbowlquestions.util.MutableClock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The shipped {@code application.yml} binds to the questions rows of the plan's
 * policy and quota tables (m4-limits sections 2.2, 2.3, 2.10), its env
 * placeholders work, and the limiter beans wire up with a replaceable
 * {@link Clock} and without Redis.
 */
class LimitsConfigurationTest {

    private static final String MAIN_YAML = "src/main/resources/application.yml";

    @Test
    void shippedPoliciesMatchThePlan() throws IOException {
        RateLimitProperties rl = bind(Map.of()).rl();

        assertThat(rl.isEnabled()).isTrue();
        assertThat(rl.getRedis().getTimeout()).isEqualTo(Duration.ofMillis(200));
        assertThat(rl.getServiceClients()).containsExactly("sockbowl-game-backend");
        assertThat(rl.getEvents().getService()).isEqualTo("questions");
        assertThat(rl.getExempt()).containsExactly("/actuator/health", "/actuator/health/**");
        assertThat(rl.getTierMultipliers()).containsEntry(Tier.GUEST, 1.0).containsEntry(Tier.PLAYER, 1.0)
                .containsEntry(Tier.AUTHOR, 2.0).containsEntry(Tier.MODERATOR, 3.0).containsEntry(Tier.ADMIN, 10.0);

        assertPolicy(rl, "default", 120, Duration.ofMinutes(1), KeyBy.USER_OR_IP);
        assertPolicy(rl, "service", 6000, Duration.ofMinutes(1), KeyBy.USER);
        assertPolicy(rl, "graphql-http", 300, Duration.ofMinutes(1), KeyBy.USER_OR_IP);
        assertThat(rl.multiplierFor(rl.policy("graphql-http"), Tier.SERVICE)).isEqualTo(20.0);
        assertThat(rl.multiplierFor(rl.policy("graphql-http"), Tier.AUTHOR)).isEqualTo(2.0);
        assertPolicy(rl, "graphql-read", 240, Duration.ofMinutes(1), KeyBy.USER_OR_IP);
        assertPolicy(rl, "graphql-write", 60, Duration.ofMinutes(1), KeyBy.USER);
        assertPolicy(rl, "ai-generate", 10, Duration.ofHours(1), KeyBy.USER);
        assertThat(rl.policy("ai-generate").isFailClosed()).isTrue();
        assertPolicy(rl, "import", 10, Duration.ofHours(1), KeyBy.USER);
        assertPolicy(rl, "import-ip", 20, Duration.ofHours(1), KeyBy.IP);
        assertPolicy(rl, "bank-read", 30, Duration.ofMinutes(1), KeyBy.USER_OR_IP);
        assertThat(rl.getPolicies().entrySet()).filteredOn(e -> e.getValue().isFailClosed())
                .extracting(Map.Entry::getKey).containsExactly("ai-generate");

        assertThat(rl.getRoutes()).extracting(RateLimitProperties.Route::getPattern).containsExactly(
                "/graphql",
                "/api/qbreader/import-random",
                "/api/qbreader/count",
                "/api/qbreader/category-counts",
                "/api/qbreader/taxonomy-counts",
                "/api/qbreader/stats",
                "/api/qbreader/dimensions");
        RateLimitProperties.Route graphql = rl.getRoutes().get(0);
        assertThat(graphql.getMethod()).isEqualTo("POST");
        assertThat(graphql.getPolicies()).containsExactly("graphql-http");
        assertThat(graphql.isFallback()).isFalse();
        assertThat(rl.getRoutes().get(1).getPolicies()).containsExactly("import", "import-ip");
        assertThat(rl.getRoutes().subList(1, rl.getRoutes().size()))
                .allMatch(RateLimitProperties.Route::isFallback);
        assertThat(rl.getRoutes().subList(2, rl.getRoutes().size()))
                .allSatisfy(r -> assertThat(r.getPolicies()).containsExactly("bank-read"));
        // ai-generate is charged by the AI guard (WP-Q3) only, never by a route.
        assertThat(rl.getRoutes()).noneMatch(r -> r.getPolicies().contains("ai-generate"));
    }

    @Test
    void graphQlFieldMapAndCapsAreShipped() throws IOException {
        Bound bound = bind(Map.of());
        Binder binder = Binder.get(bound.env());
        assertThat(binder.bind("sockbowl.ratelimit.graphql.fields", Map.class).get())
                .containsEntry("generateAndAddTossup", "graphql-write");
        assertThat(bound.env().getProperty("sockbowl.ratelimit.graphql.default-query-policy")).isEqualTo("graphql-read");
        assertThat(bound.env().getProperty("sockbowl.ratelimit.graphql.default-mutation-policy"))
                .isEqualTo("graphql-write");
        assertThat(bound.env().getProperty("sockbowl.ratelimit.graphql.max-depth", Integer.class)).isEqualTo(15);
        assertThat(bound.env().getProperty("sockbowl.ratelimit.graphql.max-complexity", Integer.class)).isPositive();
    }

    @Test
    void shippedQuotasMatchD10() throws IOException {
        QuotaProperties q = bind(Map.of()).quota();

        assertThat(q.isEnabled()).isTrue();
        assertThat(q.getDailyTtl()).isEqualTo(Duration.ofHours(48));
        for (Tier none : List.of(Tier.GUEST, Tier.PLAYER)) {
            assertThat(q.defaultLimit(none, "ai.generations")).isZero();
            assertThat(q.defaultLimit(none, "imports")).isZero();
            assertThat(q.defaultLimit(none, "packets-owned")).isZero();
        }
        for (Tier writer : List.of(Tier.AUTHOR, Tier.MODERATOR)) {
            assertThat(q.defaultLimit(writer, "ai.generations")).isEqualTo(20);
            assertThat(q.defaultLimit(writer, "imports")).isEqualTo(10);
            assertThat(q.defaultLimit(writer, "packets-owned")).isEqualTo(300);
        }
        assertThat(q.defaultLimit(Tier.ADMIN, "ai.generations")).isEqualTo(-1);
        assertThat(q.defaultLimit(Tier.ADMIN, "imports")).isEqualTo(-1);
        assertThat(q.defaultLimit(Tier.ADMIN, "packets-owned")).isEqualTo(-1);
        assertThat(q.defaultLimit(Tier.SERVICE, "packets-owned")).isEqualTo(-1);
        assertThat(q.defaultLimit(Tier.AUTHOR, "no-such-metric")).isZero();
    }

    @Test
    void aiServerKeyAndBanKeysAreShipped() throws IOException {
        StandardEnvironment env = bind(Map.of()).env();
        assertThat(env.getProperty("sockbowl.ai.server-key.daily-budget", Integer.class)).isEqualTo(200);
        assertThat(env.getProperty("sockbowl.ai.server-key.allowed-models"))
                .isEqualTo(env.getProperty("spring.ai.openai.chat.options.model")).isNotBlank();
        assertThat(env.getProperty("sockbowl.ai.max-topic-length", Integer.class)).isEqualTo(200);
        assertThat(env.getProperty("sockbowl.ai.max-context-length", Integer.class)).isEqualTo(2000);
        assertThat(env.getProperty("sockbowl.ai.max-question-count", Integer.class)).isEqualTo(30);
        assertThat(Binder.get(env).bind("sockbowl.ai.inflight-ttl", Duration.class).get()).isEqualTo(Duration.ofSeconds(660));
        assertThat(Binder.get(env).bind("sockbowl.ai.concurrency-retry-after", Duration.class).get())
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(Binder.get(env).bind("sockbowl.bans.cache-ttl", Duration.class).get()).isEqualTo(Duration.ofSeconds(30));
        assertThat(Binder.get(env).bind("sockbowl.ipban.refresh-interval", Duration.class).get()).isEqualTo(Duration.ofSeconds(15));
        assertThat(env.getProperty("sockbowl.admin.usage.max-subs", Integer.class)).isEqualTo(100);
        assertThat(env.getProperty("spring.data.redis.host")).isEqualTo("localhost");
        assertThat(env.getProperty("spring.data.redis.port", Integer.class)).isEqualTo(6379);
        assertThat(env.getProperty("spring.data.redis.database", Integer.class)).isZero();
    }

    @Test
    void envPlaceholdersOverrideTheDefaults() throws IOException {
        Bound bound = bind(Map.ofEntries(
                Map.entry("SOCKBOWL_RATELIMIT_ENABLED", "false"),
                Map.entry("SOCKBOWL_QUOTA_ENABLED", "false"),
                Map.entry("SOCKBOWL_RL_DEFAULT_CAPACITY", "10000"),
                Map.entry("SOCKBOWL_RL_GRAPHQL_HTTP_CAPACITY", "5000"),
                Map.entry("SOCKBOWL_RL_GRAPHQL_READ_CAPACITY", "5000"),
                Map.entry("SOCKBOWL_RL_GRAPHQL_WRITE_CAPACITY", "1000"),
                Map.entry("SOCKBOWL_RL_AI_GENERATE_CAPACITY", "3"),
                Map.entry("SOCKBOWL_RL_AI_GENERATE_REFILL_PERIOD", "20s"),
                Map.entry("SOCKBOWL_RL_IMPORT_CAPACITY", "4"),
                Map.entry("SOCKBOWL_RL_BANK_READ_CAPACITY", "7"),
                Map.entry("SOCKBOWL_RL_SERVICE_CLIENTS", "a,b"),
                Map.entry("SOCKBOWL_QUOTA_AUTHOR_AI_GENERATIONS", "2"),
                Map.entry("SOCKBOWL_QUOTA_AUTHOR_PACKETS_OWNED", "5"),
                Map.entry("SOCKBOWL_AI_SERVER_DAILY_BUDGET", "2"),
                Map.entry("SOCKBOWL_AI_SERVER_ALLOWED_MODELS", "m1,m2"),
                Map.entry("SOCKBOWL_REDIS_HOST", "redis"),
                Map.entry("SOCKBOWL_REDIS_PORT", "6380"),
                Map.entry("SOCKBOWL_DATABASE", "3")));
        RateLimitProperties rl = bound.rl();
        QuotaProperties q = bound.quota();

        assertThat(rl.isEnabled()).isFalse();
        assertThat(q.isEnabled()).isFalse();
        assertThat(rl.policy("default").getCapacity()).isEqualTo(10000);
        assertThat(rl.policy("graphql-http").getCapacity()).isEqualTo(5000);
        assertThat(rl.policy("graphql-read").getCapacity()).isEqualTo(5000);
        assertThat(rl.policy("graphql-write").getCapacity()).isEqualTo(1000);
        assertThat(rl.policy("ai-generate").getCapacity()).isEqualTo(3);
        assertThat(rl.policy("ai-generate").getRefillPeriod()).isEqualTo(Duration.ofSeconds(20));
        assertThat(rl.policy("import").getCapacity()).isEqualTo(4);
        assertThat(rl.policy("bank-read").getCapacity()).isEqualTo(7);
        assertThat(rl.getServiceClients()).containsExactly("a", "b");
        assertThat(q.defaultLimit(Tier.AUTHOR, "ai.generations")).isEqualTo(2);
        assertThat(q.defaultLimit(Tier.AUTHOR, "packets-owned")).isEqualTo(5);
        assertThat(bound.env().getProperty("sockbowl.ai.server-key.daily-budget")).isEqualTo("2");
        assertThat(bound.env().getProperty("sockbowl.ai.server-key.allowed-models")).isEqualTo("m1,m2");
        assertThat(bound.env().getProperty("spring.data.redis.host")).isEqualTo("redis");
        assertThat(bound.env().getProperty("spring.data.redis.port")).isEqualTo("6380");
        assertThat(bound.env().getProperty("spring.data.redis.database")).isEqualTo("3");
    }

    @Test
    void shippedConfigPassesTheStartupValidator() throws IOException {
        Bound bound = bind(Map.of());
        assertThatCode(() -> new LimitsStartupValidator(bound.env(), bound.rl()).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThat(bound.env().getProperty("server.forward-headers-strategy")).isEqualTo("none");
    }

    @Test
    void nativeStrategyFromEnvWithoutAProxyRegexFailsTheValidator() throws IOException {
        Bound bound = bind(Map.of("SOCKBOWL_FORWARD_HEADERS_STRATEGY", "native"));
        assertThat(bound.env().getProperty("server.tomcat.remoteip.internal-proxies")).isEmpty();
        assertThatThrownBy(() -> new LimitsStartupValidator(bound.env(), bound.rl()).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);

        Bound trusted = bind(Map.of("SOCKBOWL_FORWARD_HEADERS_STRATEGY", "native",
                "SOCKBOWL_TRUSTED_PROXIES_REGEX", "10\\.0\\.0\\.2"));
        assertThatCode(() -> new LimitsStartupValidator(trusted.env(), trusted.rl()).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    // --- bean wiring -------------------------------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LimitsClockConfig.class, LimitsFallbackAutoConfiguration.class))
            .withUserConfiguration(RateLimitRedisConfig.class)
            .withPropertyValues("spring.data.redis.port=1");

    @Test
    void wiresTheLimiterWithASystemClockAndNoOpCheckersByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(RateLimitService.class).hasSingleBean(LocalBucketRegistry.class)
                    .hasSingleBean(RateLimitEventRecorder.class).hasSingleBean(QuotaService.class);
            assertThat(ctx.getBean(Clock.class)).isEqualTo(Clock.systemUTC());
            assertThat(ctx.getBean(IpBanChecker.class)).isSameAs(IpBanChecker.NONE);
            assertThat(ctx.getBean(SubjectBanChecker.class)).isSameAs(SubjectBanChecker.NONE);
            assertThat(ctx.getBean(UsageTouchTracker.class)).isSameAs(UsageTouchTracker.NONE);
            // Connecting is lazy: no Redis is needed to start.
            assertThat(ctx.getBean(RateLimitService.class)
                    .tryConsume("anything", LimitSubject.guest("192.0.2.1")).allowed()).isTrue();
        });
    }

    @Test
    void anApplicationClockAndRealBanCheckersReplaceTheDefaults() {
        runner.withUserConfiguration(Overrides.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(Clock.class);
            assertThat(ctx.getBean(Clock.class)).isInstanceOf(MutableClock.class);
            assertThat(ctx.getBean(RateLimitTimeMeter.class).currentTimeNanos())
                    .isEqualTo(Instant.parse("2026-09-27T12:00:00Z").getEpochSecond() * 1_000_000_000L);
            assertThat(ctx.getBean(IpBanChecker.class)).isNotSameAs(IpBanChecker.NONE);
            assertThat(ctx.getBean(SubjectBanChecker.class)).isNotSameAs(SubjectBanChecker.NONE);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class Overrides {
        @Bean
        Clock testClock() {
            return new MutableClock(Instant.parse("2026-09-27T12:00:00Z"));
        }

        @Bean
        IpBanChecker realIpBans() {
            return raw -> Optional.of(Instant.MAX);
        }

        @Bean
        SubjectBanChecker realSubjectBans() {
            return sub -> Optional.empty();
        }
    }

    // --- helpers -------------------------------------------------------------------

    private record Bound(StandardEnvironment env, RateLimitProperties rl, QuotaProperties quota) {
    }

    private static Bound bind(Map<String, Object> envOverrides) throws IOException {
        StandardEnvironment env = new StandardEnvironment();
        // Keep the real OS environment out of it so the test is hermetic.
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new MapPropertySource("env", envOverrides));
        for (PropertySource<?> source : new YamlPropertySourceLoader()
                .load("main-application-yml", new FileSystemResource(MAIN_YAML))) {
            env.getPropertySources().addLast(source);
        }
        Binder binder = Binder.get(env);
        RateLimitProperties rl = binder.bind("sockbowl.ratelimit", RateLimitProperties.class)
                .orElseGet(RateLimitProperties::new);
        QuotaProperties quota = binder.bind("sockbowl.quota", QuotaProperties.class).orElseGet(QuotaProperties::new);
        return new Bound(env, rl, quota);
    }

    private static void assertPolicy(RateLimitProperties rl, String name, long capacity, Duration period,
                                     KeyBy keyBy) {
        PolicySpec spec = rl.policy(name);
        assertThat(spec).as(name).isNotNull();
        assertThat(spec.getCapacity()).as(name + " capacity").isEqualTo(capacity);
        assertThat(spec.effectiveRefillTokens()).as(name + " refill tokens").isEqualTo(capacity);
        assertThat(spec.getRefillPeriod()).as(name + " refill period").isEqualTo(period);
        assertThat(spec.getKeyBy()).as(name + " key").isEqualTo(keyBy);
    }
}
