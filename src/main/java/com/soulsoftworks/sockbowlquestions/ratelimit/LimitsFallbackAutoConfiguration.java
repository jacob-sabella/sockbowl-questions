package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * No-op {@link IpBanChecker}, {@link SubjectBanChecker} and
 * {@link UsageTouchTracker} used until a real implementation is present
 * (WP-G4 registers the ban checkers only with auth on; WP-G5's
 * {@code UsageTracker} is unconditional but this keeps every caller safe even
 * if it is ever missing). An auto-configuration, like {@link
 * LimitsClockConfig}, so the {@code @ConditionalOnMissingBean} checks run
 * after every scanned bean has been registered.
 */
@AutoConfiguration
public class LimitsFallbackAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(IpBanChecker.class)
    public IpBanChecker noOpIpBanChecker() {
        return IpBanChecker.NONE;
    }

    @Bean
    @ConditionalOnMissingBean(SubjectBanChecker.class)
    public SubjectBanChecker noOpSubjectBanChecker() {
        return SubjectBanChecker.NONE;
    }

    @Bean
    @ConditionalOnMissingBean(UsageTouchTracker.class)
    public UsageTouchTracker noOpUsageTouchTracker() {
        return UsageTouchTracker.NONE;
    }
}
