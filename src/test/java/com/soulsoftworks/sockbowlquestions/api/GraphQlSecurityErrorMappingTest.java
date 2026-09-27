package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.JwtDecoderConfig;
import com.soulsoftworks.sockbowlquestions.config.SecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketAuthorizationService;
import com.soulsoftworks.sockbowlquestions.service.PacketAuthoringService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.security.GraphQlWebMvcSecurityAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A {@code @PreAuthorize} denial inside a GraphQL data fetcher must come back as a
 * typed GraphQL error, not a generic {@code INTERNAL_ERROR}: {@code UNAUTHORIZED}
 * for an anonymous caller and {@code FORBIDDEN} for an authenticated one without
 * the authority or ownership. Spring Boot registers Spring GraphQL's
 * {@code SecurityDataFetcherExceptionResolver} through
 * {@link GraphQlWebMvcSecurityAutoConfiguration}; this test runs the real HTTP
 * {@code POST /graphql} path (through {@link SecurityConfig}) to prove it is in
 * effect.
 */
@WebMvcTest(controllers = PacketAuthoringController.class, properties = "sockbowl.auth.enabled=true")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class,
        GraphQlWebMvcSecurityAutoConfiguration.class})
@Import({SecurityConfig.class, JwtDecoderConfig.class, PacketAuthorizationService.class})
class GraphQlSecurityErrorMappingTest {

    private static final String CREATE =
            "{\"query\":\"mutation { createPacket(input: {name: \\\"P\\\"}) { id name } }\"}";
    private static final String DELETE =
            "{\"query\":\"mutation { deletePacket(id: \\\"p1\\\") }\"}";

    @Autowired
    private MockMvc mvc;

    @MockitoBean private PacketAuthoringService authoringService;
    @MockitoBean private PacketRepository packetRepository;

    private ResultActions graphql(String body, RequestPostProcessor... auth) throws Exception {
        var req = post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body);
        for (RequestPostProcessor p : auth) {
            req = req.with(p);
        }
        ResultActions actions = mvc.perform(req);
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            // Spring GraphQL may complete asynchronously; the ASYNC re-dispatch must
            // also pass SecurityConfig (it is permitted as a re-dispatch).
            return mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
        }
        return actions.andExpect(status().isOk());
    }

    private static RequestPostProcessor as(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                java.util.Arrays.stream(authorities)
                        .map(a -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority(a))
                        .toList());
    }

    @Test
    void anonymous_mutation_is_UNAUTHORIZED() throws Exception {
        graphql(CREATE)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(authoringService, never()).createPacket(any(), any(), any());
    }

    @Test
    void wrong_role_mutation_is_FORBIDDEN() throws Exception {
        graphql(CREATE, as("player-sub", "packet:read", "game:host"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService, never()).createPacket(any(), any(), any());
    }

    @Test
    void right_role_mutation_succeeds() throws Exception {
        Packet p = new Packet();
        p.setId("p-new");
        p.setName("P");
        when(authoringService.createPacket(any(), eq("author-sub"), any())).thenReturn(p);

        graphql(CREATE, as("author-sub", "packet:create"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.createPacket.id").value("p-new"));
    }

    @Test
    void author_deleting_ownerless_packet_is_FORBIDDEN() throws Exception {
        Packet ownerless = new Packet();
        ownerless.setId("p1");
        when(packetRepository.findById("p1")).thenReturn(Optional.of(ownerless));

        graphql(DELETE, as("author-sub", "packet:delete"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService, never()).deletePacket(any(), any());
    }
}
