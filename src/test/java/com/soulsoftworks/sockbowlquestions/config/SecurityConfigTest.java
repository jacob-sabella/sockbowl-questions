package com.soulsoftworks.sockbowlquestions.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused MockMvc slice proving the {@link SecurityConfig} wiring
 * (401 unauthenticated / 403 wrong authority / 200 correct authority)
 * without booting the full application context, which requires Neo4j.
 *
 * <p>Uses the top-level {@link SecurityProbeController} rather than a
 * nested controller, since Spring Boot's {@code TestTypeExcludeFilter}
 * excludes classes nested inside JUnit test classes from the slice's
 * component scan.
 */
@WebMvcTest(controllers = SecurityProbeController.class, properties = "sockbowl.auth.enabled=true")
@Import(SecurityConfig.class)
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void unauthenticated_is_401() throws Exception {
        mvc.perform(get("/probe/read")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrong_authority_is_403() throws Exception {
        mvc.perform(get("/probe/read").with(jwt().authorities(new SimpleGrantedAuthority("packet:create"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void right_authority_is_200() throws Exception {
        mvc.perform(get("/probe/read").with(jwt().authorities(new SimpleGrantedAuthority("packet:read"))))
                .andExpect(status().isOk());
    }
}
