package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the single {@link RequestGuardFilter} instance that
 * {@code SecurityConfig} and {@code NoSecurityConfig} add to their chains
 * (plan m4-limits section 2.2, WP-G2).
 *
 * <p>Any {@code Filter} bean is also registered by Spring Boot as a plain
 * servlet filter; the disabled {@link FilterRegistrationBean} stops that, so
 * the guard runs exactly once, inside the security chain, after bearer
 * authentication.
 */
@Configuration
public class RequestGuardFilterConfig {

    @Bean
    public RequestGuardFilter requestGuardFilter(RateLimitService rateLimitService,
                                                 RateLimitProperties properties,
                                                 LimitSubjectResolver subjectResolver,
                                                 ClientIpResolver clientIpResolver,
                                                 IpBanChecker ipBanChecker,
                                                 SubjectBanChecker subjectBanChecker,
                                                 RateLimitEventRecorder eventRecorder,
                                                 UsageTouchTracker usageTouchTracker) {
        return new RequestGuardFilter(rateLimitService, properties, subjectResolver, clientIpResolver,
                ipBanChecker, subjectBanChecker, eventRecorder, usageTouchTracker);
    }

    @Bean
    public FilterRegistrationBean<RequestGuardFilter> requestGuardFilterRegistration(RequestGuardFilter filter) {
        FilterRegistrationBean<RequestGuardFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
