package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LimitsStartupValidatorTest {

    private final RateLimitProperties properties = new RateLimitProperties();

    @Test
    void nativeStrategyWithABlankProxyRegexFailsStartup() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "  ");
        assertThatThrownBy(() -> new LimitsStartupValidator(env, properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.tomcat.remoteip.internal-proxies");
    }

    @Test
    void nativeStrategyWithNoProxyRegexAtAllFailsStartup() {
        MockEnvironment env = new MockEnvironment().withProperty("server.forward-headers-strategy", "NATIVE");
        assertThatThrownBy(() -> new LimitsStartupValidator(env, properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void nativeStrategyWithAnExplicitRegexStarts() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "native")
                .withProperty("server.tomcat.remoteip.internal-proxies", "172\\.18\\.0\\.\\d+");
        assertThatCode(() -> new LimitsStartupValidator(env, properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void frameworkStrategyFailsStartupEvenWithAnExplicitRegex() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "framework")
                .withProperty("server.tomcat.remoteip.internal-proxies", "172\\.18\\.0\\.\\d+");
        assertThatThrownBy(() -> new LimitsStartupValidator(env, properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("server.forward-headers-strategy=framework");
    }

    @Test
    void defaultStrategyNoneStartsWithABlankRegex() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("server.forward-headers-strategy", "none")
                .withProperty("server.tomcat.remoteip.internal-proxies", "");
        assertThatCode(() -> new LimitsStartupValidator(env, properties).afterPropertiesSet())
                .doesNotThrowAnyException();
        assertThatCode(() -> new LimitsStartupValidator(new MockEnvironment(), properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void invalidPoliciesAndRoutesFailStartupWhenLimitingIsEnabled() {
        properties.getPolicies().put("zero", PolicySpec.of(0, Duration.ofMinutes(1), KeyBy.IP));
        properties.getPolicies().put("no-period", PolicySpec.of(5, Duration.ZERO, KeyBy.IP));
        RateLimitProperties.Route route = new RateLimitProperties.Route();
        route.setPattern("/api/x");
        route.setPolicies(List.of("missing"));
        properties.getRoutes().add(route);

        assertThatThrownBy(() -> new LimitsStartupValidator(new MockEnvironment(), properties).afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("zero.capacity")
                .hasMessageContaining("no-period.refill-period")
                .hasMessageContaining("undefined policy 'missing'");

        properties.setEnabled(false);
        assertThatCode(() -> new LimitsStartupValidator(new MockEnvironment(), properties).afterPropertiesSet())
                .doesNotThrowAnyException();
    }
}
