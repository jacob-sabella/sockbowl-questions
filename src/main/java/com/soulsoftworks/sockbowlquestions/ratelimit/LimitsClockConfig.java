package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import java.time.Clock;

/**
 * The {@link Clock} every limiter, quota and TTL computation reads (plan
 * m4-limits section 2.2). Registered as an auto-configuration (listed in
 * {@code META-INF/spring/...AutoConfiguration.imports}, and so skipped by
 * component scanning) so that {@code @ConditionalOnMissingBean} is evaluated
 * after the application's own beans: a test that declares its own
 * {@code Clock} bean (a mutable test clock) replaces this one cleanly.
 */
@AutoConfiguration
public class LimitsClockConfig {

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock limitsClock() {
        return Clock.systemUTC();
    }
}
