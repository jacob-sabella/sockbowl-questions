package com.soulsoftworks.sockbowlquestions.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The same guard is wired into the auth-off chain ({@code NoSecurityConfig}):
 * with {@code sockbowl.auth.enabled=false} the REST policies still trip and
 * recover, keyed by address.
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=false")
@AutoConfigureMockMvc
@Import(QuestionsRequestGuardITSupport.ClockConfig.class)
class QuestionsRequestGuardFilterAuthOffIT extends QuestionsRequestGuardITSupport {

    @Autowired
    FilterChainProxy filterChainProxy;

    @Test
    void guardIsInTheAuthOffChain() {
        assertThat(filterChainProxy.getFilterChains().get(0).getFilters())
                .anyMatch(RequestGuardFilter.class::isInstance);
    }

    @Test
    void bankReadTripsAndRecoversWithAuthOff() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 30; i++) {
            assertThat(mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn().getResponse().getStatus())
                    .as("bank read #%d", i).isEqualTo(200);
        }
        assertRateLimited(mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn(), "bank-read", 30);
        CLOCK.advance(Duration.ofMinutes(1));
        assertThat(mvc.perform(get(CATEGORY_COUNTS).with(from(ip))).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }

    @Test
    void importRandomTripsPerAddressWithAuthOff() throws Exception {
        String ip = nextIp();
        for (int i = 1; i <= 10; i++) {
            MvcResult ok = mvc.perform(post(IMPORT_RANDOM).with(from(ip)).contentType(json()).content(importBody()))
                    .andReturn();
            assertThat(ok.getResponse().getStatus()).as("import #%d", i).isEqualTo(200);
        }
        assertRateLimited(mvc.perform(post(IMPORT_RANDOM).with(from(ip)).contentType(json()).content(importBody()))
                .andReturn(), "import", 10);
    }

    @Test
    void graphQlHttpAppliesWithAuthOff() throws Exception {
        MvcResult ok = mvc.perform(post(GRAPHQL).with(from(nextIp())).contentType(json()).content(graphQlBody()))
                .andReturn();
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getHeader(LimitErrorResponses.X_RATE_LIMIT_POLICY)).isEqualTo("graphql-http");
    }
}
