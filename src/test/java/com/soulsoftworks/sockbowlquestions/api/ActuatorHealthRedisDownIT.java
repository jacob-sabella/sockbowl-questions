package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Q-V1-03 (the health/D12 decision): boots the MAIN {@code application.yml}'s
 * {@code management.health.redis.enabled=false} directly — this class adds no
 * {@code management.health.redis.*} override of its own, so it does NOT inherit
 * the old {@code src/test/resources/config/application.yml} override the fix
 * removed as redundant. Redis is genuinely unreachable (the shared test
 * classpath config already points {@code spring.data.redis} at {@code 127.0.0.1:1}),
 * and {@code sockbowl.ratelimit.enabled=true} here so the limiter would really
 * touch Redis on an ordinary request, same as in production. {@code GET
 * /actuator/health} must still answer 200 UP: game's Redis OM health check is
 * still meaningful (GameSession state lives in Redis), but every Redis use here
 * (limiters, quotas, the ban mirror) fails open per D12, and AI generation
 * already fails closed per request with its own 503 {@code limiter_unavailable}
 * rather than through the compose healthcheck.
 */
@SpringBootTest(properties = {"sockbowl.ratelimit.enabled=true"})
@AutoConfigureMockMvc
class ActuatorHealthRedisDownIT extends Neo4jContainerTestBase {

    @Autowired
    MockMvc mvc;

    @Test
    void healthStaysUpWithRedisUnreachable() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
