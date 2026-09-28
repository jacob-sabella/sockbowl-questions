package com.soulsoftworks.sockbowlquestions.support;

import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mock-JWT helpers for GraphQL authorization tests over the real {@code POST /graphql}
 * MockMvc path (M3 Q6, PB-21). M2 only ships real-token bases
 * ({@link KeycloakAuthITBase}); this is the lightweight counterpart for tests that want
 * to name the exact authorities a caller holds without a Keycloak container.
 *
 * <p>{@link Caller} mirrors the composite roles in {@code sockbowl-docker/keycloak/rbac-model.json}
 * after the M3 D4 move ({@code taxonomy:manage} on moderator, not author), flattened to
 * the permission authorities that {@code SecurityConfig}'s converter produces.
 */
public final class GraphQlAuthTestSupport {

    private static final ObjectMapper JSON = new ObjectMapper();

    private GraphQlAuthTestSupport() {
    }

    private static final String[] PLAYER_AUTHORITIES = {"packet:read", "game:host"};
    private static final String[] AUTHOR_AUTHORITIES = concat(PLAYER_AUTHORITIES,
            "packet:create", "packet:update", "packet:delete", "question:generate");
    private static final String[] MODERATOR_AUTHORITIES = concat(PLAYER_AUTHORITIES,
            "user:ban", "taxonomy:manage");
    private static final String[] ADMIN_AUTHORITIES = concat(concat(AUTHOR_AUTHORITIES, MODERATOR_AUTHORITIES),
            "admin:access", "packet:manage-any");

    /**
     * The callers of the M3 permission matrix. {@link #AUTHOR_OWNER} owns the test
     * fixtures; {@link #AUTHOR_OTHER} holds the same authorities without owning them.
     * {@link #SERVICE} is the game backend's service token (D2), included for the
     * EPHEMERAL (D15) rows.
     */
    public enum Caller {
        ANONYMOUS(null),
        PLAYER("player-sub", PLAYER_AUTHORITIES),
        AUTHOR_OWNER("author-sub", AUTHOR_AUTHORITIES),
        AUTHOR_OTHER("author2-sub", AUTHOR_AUTHORITIES),
        MODERATOR("moderator-sub", MODERATOR_AUTHORITIES),
        ADMIN("admin-sub", ADMIN_AUTHORITIES),
        SERVICE("service-account-sockbowl-game-backend", "packet:read", "packet:read-answers");

        private final String sub;
        private final List<String> authorities;

        Caller(String sub, String... authorities) {
            this.sub = sub;
            this.authorities = List.of(authorities);
        }

        /** The JWT {@code sub}, or null for {@link #ANONYMOUS}. */
        public String sub() {
            return sub;
        }

        public List<String> authorities() {
            return authorities;
        }

        /** The mock-JWT post-processor for this caller; empty for {@link #ANONYMOUS}. */
        public RequestPostProcessor[] auth() {
            return sub == null ? new RequestPostProcessor[0]
                    : new RequestPostProcessor[]{as(sub, authorities.toArray(String[]::new))};
        }
    }

    /** A mock JWT with subject {@code sub}, a {@code preferred_username}, and exactly these authorities. */
    public static RequestPostProcessor as(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub).claim("preferred_username", sub.replace("-sub", "")))
                .authorities(Arrays.stream(authorities)
                        .map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a))
                        .toList());
    }

    /** {@code POST /graphql} without variables. */
    public static ResultActions graphql(MockMvc mvc, String query, RequestPostProcessor... auth) throws Exception {
        return graphql(mvc, query, null, auth);
    }

    /**
     * {@code POST /graphql}, following Spring GraphQL's async dispatch when the request
     * completes asynchronously (the re-dispatch passes {@code SecurityConfig} as a
     * re-dispatch, so it needs no token of its own). Always expects HTTP 200: GraphQL
     * errors come back in the body.
     */
    public static ResultActions graphql(MockMvc mvc, String query, Map<String, Object> variables,
                                        RequestPostProcessor... auth) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", query);
        if (variables != null) {
            body.put("variables", variables);
        }
        MockHttpServletRequestBuilder req = post("/graphql")
                .contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(body));
        for (RequestPostProcessor p : auth) {
            req = req.with(p);
        }
        ResultActions actions = mvc.perform(req);
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
        }
        return actions.andExpect(status().isOk());
    }

    private static String[] concat(String[] base, String... more) {
        return Stream.concat(Arrays.stream(base), Arrays.stream(more)).distinct().toArray(String[]::new);
    }
}
