package com.soulsoftworks.sockbowlquestions.config;

import com.soulsoftworks.sockbowlquestions.api.BankDimensionsController;
import com.soulsoftworks.sockbowlquestions.api.BankStatsController;
import com.soulsoftworks.sockbowlquestions.api.PacketGenerationController;
import com.soulsoftworks.sockbowlquestions.api.QbreaderController;
import com.soulsoftworks.sockbowlquestions.repository.BankDimensionsRepository;
import com.soulsoftworks.sockbowlquestions.repository.BankStatsRepository;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService;
import com.soulsoftworks.sockbowlquestions.service.QbreaderImportService.ImportOutcome;
import com.soulsoftworks.sockbowlquestions.service.QuestionGenerationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Focused MockMvc slice proving the {@link SecurityConfig} wiring without booting
 * the full application context, which requires Neo4j.
 *
 * <p>Two things are covered:
 * <ul>
 *   <li><strong>The HTTP perimeter</strong> (AUTH-08): deny-by-default URL rules,
 *       exercised against the real bank and import controllers with mocked
 *       services. Unlisted paths are 401 anonymous and 403 authenticated, the
 *       aggregate bank endpoints and {@code import-random} (D15) are open, and
 *       {@code generate} needs a bearer.</li>
 *   <li><strong>Method security and authority mapping</strong>
 *       (401 unauthenticated / 403 wrong authority / 200 correct authority) via
 *       {@link SecurityProbeController}. The probe path isn't in the deny-by-default
 *       allowlist, so {@link ProbeSecurityTestConfig} routes {@code /probe/**} through
 *       a test chain that leaves every decision to {@code @PreAuthorize}. An
 *       {@code AccessDeniedException} against an anonymous principal is handed to
 *       the resource server's entry point, so it surfaces as 401, not 403.</li>
 * </ul>
 *
 * <p>Probe controllers are top-level classes because Spring Boot's
 * {@code TestTypeExcludeFilter} excludes classes nested inside JUnit test classes
 * from the slice's component scan.
 */
@WebMvcTest(controllers = {SecurityProbeController.class, BankStatsController.class,
        BankDimensionsController.class, QbreaderController.class, PacketGenerationController.class},
        properties = {"sockbowl.auth.enabled=true", "sockbowl.cors.allowed-origins=http://app.test"})
@Import({SecurityConfig.class, JwtDecoderConfig.class, ProbeSecurityTestConfig.class, WebConfig.class})
class SecurityConfigTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean private BankStatsRepository bankStatsRepository;
    @MockitoBean private BankDimensionsRepository bankDimensionsRepository;
    @MockitoBean private QbreaderImportService importService;
    @MockitoBean private QuestionGenerationService questionGenerationService;
    @MockitoBean private AiSecurityProperties aiSecurityProperties;

    // --- method security via the probe controller (unchanged contract) -------

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

    @Test
    void realm_roles_claim_maps_to_raw_and_ROLE_authorities() {
        // The real keycloakJwtAuthenticationConverter, fed a Keycloak-shaped token.
        Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject("sub-1")
                .claim("realm_access", Map.of("roles", List.of("packet:create", "author")))
                .build();
        var authorities = new SecurityConfig().keycloakJwtAuthenticationConverter().convert(token)
                .getAuthorities().stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(authorities).contains("packet:create", "ROLE_packet:create", "author", "ROLE_author");
    }

    // --- deny-by-default perimeter --------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"/", "/nope", "/api/unknown", "/api/qbreader", "/actuator/env",
            "/actuator/beans", "/graphiql", "/api/v1/test", "/login", "/oauth2/authorization/keycloak"})
    void unknown_path_is_401_anonymous(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/nope", "/api/unknown", "/actuator/env", "/graphiql"})
    void unknown_path_is_403_authenticated(String path) throws Exception {
        mvc.perform(get(path).with(jwt().authorities(new SimpleGrantedAuthority("packet:manage-any"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrong_method_on_open_path_is_denied() throws Exception {
        // Only GET is open on the stats endpoints and only POST on /graphql.
        mvc.perform(delete("/api/qbreader/stats")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/qbreader/stats")).andExpect(status().isUnauthorized());
        mvc.perform(get("/graphql")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/qbreader/stats", "/api/qbreader/dimensions",
            "/api/qbreader/category-counts", "/api/qbreader/taxonomy-counts"})
    void bank_stats_are_200_anonymous(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @Test
    void bank_count_is_200_anonymous() throws Exception {
        mvc.perform(post("/api/qbreader/count").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void import_random_is_open_anonymous_for_ephemeral_packets() throws Exception {
        // D15 amends D3: guests may generate a (game-only, EPHEMERAL) bank packet.
        Packet p = new Packet();
        p.setId("eph");
        when(importService.importRandomPacket(any(), anyInt(), anyInt(), any(), any(), anyBoolean(), any(), any(),
                any())).thenReturn(new ImportOutcome(p, List.of(), 5, 5));

        mvc.perform(post("/api/qbreader/import-random").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void import_random_with_invalid_bearer_is_401() throws Exception {
        mvc.perform(post("/api/qbreader/import-random").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void generate_is_401_anonymous_and_403_without_question_generate() throws Exception {
        mvc.perform(get("/api/packets/generate").param("topic", "x")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/packets/generate").param("topic", "x")
                        .with(jwt().authorities(new SimpleGrantedAuthority("packet:create"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void invalid_bearer_on_open_path_is_401() throws Exception {
        // A bearer, when present, is always validated, even on permitAll routes.
        mvc.perform(get("/api/qbreader/stats").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void cors_preflight_from_allowed_origin_is_not_blocked() throws Exception {
        // Preflights carry no bearer; deny-by-default must not turn them into 401s.
        mvc.perform(options("/api/qbreader/import-random")
                        .header(HttpHeaders.ORIGIN, "http://app.test")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://app.test"));
    }
}
