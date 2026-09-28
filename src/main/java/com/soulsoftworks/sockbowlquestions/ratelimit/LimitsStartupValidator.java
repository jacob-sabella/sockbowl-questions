package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Fails startup on limits config that would be silently unsafe (plan
 * m4-limits section 2.1).
 *
 * <ul>
 *   <li>{@code server.forward-headers-strategy} anything other than the default
 *       {@code none} or {@code native} with a non-blank
 *       {@code server.tomcat.remoteip.internal-proxies}: {@code framework}
 *       trusts every {@code X-Forwarded-For} unconditionally, with no
 *       trusted-proxy check at all, and {@code native} with a <b>blank</b>
 *       internal-proxies regex trusts every peer the same way — either lets any
 *       client pick its own rate-limit key (Q-V1-02, G-M4-V1-04).</li>
 *   <li>A policy with a non-positive capacity or refill period, or a route
 *       naming an undefined policy (only when rate limiting is enabled).</li>
 * </ul>
 */
@Component
public class LimitsStartupValidator implements InitializingBean {

    static final String STRATEGY = "server.forward-headers-strategy";
    static final String INTERNAL_PROXIES = "server.tomcat.remoteip.internal-proxies";

    private final Environment environment;
    private final RateLimitProperties properties;

    public LimitsStartupValidator(Environment environment, RateLimitProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        List<String> problems = new ArrayList<>();

        String strategy = environment.getProperty(STRATEGY, "none").trim().toLowerCase(Locale.ROOT);
        String proxies = environment.getProperty(INTERNAL_PROXIES);
        if ("native".equals(strategy)) {
            if (proxies == null || proxies.isBlank()) {
                problems.add(STRATEGY + "=native requires an explicit " + INTERNAL_PROXIES
                        + " regex (SOCKBOWL_TRUSTED_PROXIES_REGEX); a blank value trusts every client's"
                        + " X-Forwarded-For");
            }
        } else if (!"none".equals(strategy)) {
            problems.add(STRATEGY + "=" + strategy + " is not allowed; use 'none', or 'native' with an explicit "
                    + INTERNAL_PROXIES + " regex. 'framework' trusts every client's X-Forwarded-For with no"
                    + " trusted-proxy check at all.");
        }

        if (properties.isEnabled()) {
            for (Map.Entry<String, PolicySpec> entry : properties.getPolicies().entrySet()) {
                PolicySpec spec = entry.getValue();
                if (spec.getCapacity() <= 0) {
                    problems.add("sockbowl.ratelimit.policies." + entry.getKey() + ".capacity must be > 0");
                }
                if (spec.getRefillPeriod() == null || spec.getRefillPeriod().isNegative()
                        || spec.getRefillPeriod().isZero()) {
                    problems.add("sockbowl.ratelimit.policies." + entry.getKey() + ".refill-period must be > 0");
                }
            }
            for (int i = 0; i < properties.getRoutes().size(); i++) {
                RateLimitProperties.Route route = properties.getRoutes().get(i);
                if (route.getPattern() == null || !route.getPattern().startsWith("/")) {
                    problems.add("sockbowl.ratelimit.routes[" + i + "].pattern must be an absolute path");
                }
                for (String policy : route.getPolicies()) {
                    if (!properties.getPolicies().containsKey(policy)) {
                        problems.add("sockbowl.ratelimit.routes[" + i + "] names undefined policy '" + policy + "'");
                    }
                }
            }
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException("Invalid limits configuration: " + String.join("; ", problems));
        }
    }
}
