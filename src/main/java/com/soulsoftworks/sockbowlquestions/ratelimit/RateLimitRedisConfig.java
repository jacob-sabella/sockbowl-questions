package com.soulsoftworks.sockbowlquestions.ratelimit;

import com.soulsoftworks.sockbowlquestions.quota.QuotaProperties;
import com.soulsoftworks.sockbowlquestions.quota.QuotaService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the limiter core (plan m4-limits section 2.2): the limiter's own lazy
 * Lettuce connection to the Redis shared with sockbowl-game (host/port/db from
 * {@code spring.data.redis.*}, i.e. {@code SOCKBOWL_REDIS_HOST/PORT} and
 * {@code SOCKBOWL_DATABASE}, which must equal game's DB), the clock-backed time
 * meter, and the services built on them.
 */
@Configuration
@EnableConfigurationProperties({RateLimitProperties.class, QuotaProperties.class})
public class RateLimitRedisConfig {

    @Bean
    RateLimitTimeMeter rateLimitTimeMeter(Clock clock) {
        return new RateLimitTimeMeter(clock);
    }

    @Bean
    RateLimitRedis rateLimitRedis(
            @Value("${spring.data.redis.host:localhost}") String host,
            @Value("${spring.data.redis.port:6379}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password,
            RateLimitProperties properties,
            RateLimitTimeMeter timeMeter) {
        return new RateLimitRedis(host, port, database, password, properties.getRedis(), timeMeter);
    }

    @Bean
    BucketConfigurations bucketConfigurations(RateLimitProperties properties) {
        return new BucketConfigurations(properties);
    }

    @Bean
    RateLimitService rateLimitService(RateLimitProperties properties, RateLimitRedis redis,
                                      BucketConfigurations configurations, Clock clock) {
        return new RateLimitService(properties, redis, configurations, clock);
    }

    @Bean
    LocalBucketRegistry localBucketRegistry(RateLimitProperties properties, BucketConfigurations configurations,
                                            RateLimitTimeMeter timeMeter) {
        return new LocalBucketRegistry(properties, configurations, timeMeter);
    }

    @Bean
    RateLimitEventRecorder rateLimitEventRecorder(RateLimitRedis redis, RateLimitProperties properties,
                                                  Clock clock) {
        return new RateLimitEventRecorder(redis, properties, clock);
    }

    @Bean
    QuotaService quotaService(QuotaProperties properties, RateLimitRedis redis, Clock clock) {
        return new QuotaService(properties, redis, clock);
    }
}
