package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.NoSecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Auth-off regression ({@code sockbowl.auth.enabled=false}, the local-dev default):
 * reads behave as before visibility existed. Every packet, drafts included, comes
 * back with its answers.
 */
@WebMvcTest(controllers = GraphQLController.class, properties = "sockbowl.auth.enabled=false")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
@Import({NoSecurityConfig.class, PacketReadPolicy.class})
class GraphQlPacketReadAuthOffTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private PacketRepository packetRepository;
    @MockitoBean private DifficultyRepository difficultyRepository;
    @MockitoBean private CategoryRepository categoryRepository;
    @MockitoBean private SubcategoryRepository subcategoryRepository;

    @Test
    void anonymousReadsEverythingWithAnswers() throws Exception {
        Packet draft = packet("draft", PacketVisibility.DRAFT);
        Packet published = packet("pub", PacketVisibility.PUBLISHED);
        when(packetRepository.findListedPacketIds(List.of("EPHEMERAL"), "PUBLISHED")).thenReturn(List.of("draft", "pub"));
        when(packetRepository.findAllById(List.of("draft", "pub"))).thenReturn(List.of(draft, published));
        when(packetRepository.findById("draft")).thenReturn(Optional.of(draft));
        when(packetRepository.searchListedByName("p", List.of("EPHEMERAL"), "PUBLISHED"))
                .thenReturn(List.of(draft, published));

        graphql("{ getAllPackets { id answersRedacted tossups { tossup { answer } } } }")
                .andExpect(jsonPath("$.data.getAllPackets[*].id", containsInAnyOrder("draft", "pub")))
                .andExpect(jsonPath("$.data.getAllPackets[0].answersRedacted").value(false))
                .andExpect(jsonPath("$.data.getAllPackets[0].tossups[0].tossup.answer").value("A"));
        graphql("{ getPacketById(id: \"draft\") { id visibility tossups { tossup { answer } } } }")
                .andExpect(jsonPath("$.data.getPacketById.visibility").value("DRAFT"))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.answer").value("A"));
        graphql("{ searchPacketsByName(name: \"p\") { id } }")
                .andExpect(jsonPath("$.data.searchPacketsByName[*].id", containsInAnyOrder("draft", "pub")));

        verify(packetRepository, never()).findVisiblePacketIds(any(), any(), any());
        verify(packetRepository, never()).searchVisibleByName(any(), any(), any(), any());
    }

    @Test
    void ephemeralIsUnlistedButStillPlayableById() throws Exception {
        // Auth off has no service token, so the game reads it like everyone else.
        Packet ephemeral = packet("eph", PacketVisibility.EPHEMERAL);
        ephemeral.setOwnerId(null);
        when(packetRepository.findListedPacketIds(List.of("EPHEMERAL"), "PUBLISHED")).thenReturn(List.of("eph"));
        when(packetRepository.findAllById(List.of("eph"))).thenReturn(List.of(ephemeral));
        when(packetRepository.findById("eph")).thenReturn(Optional.of(ephemeral));

        graphql("{ getAllPackets { id } }")
                .andExpect(jsonPath("$.data.getAllPackets.length()").value(0));
        graphql("{ getPacketById(id: \"eph\") { visibility tossups { tossup { answer } } } }")
                .andExpect(jsonPath("$.data.getPacketById.visibility").value("EPHEMERAL"))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.answer").value("A"));
    }

    private ResultActions graphql(String query) throws Exception {
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(java.util.Map.of("query", query));
        ResultActions actions = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            return mvc.perform(asyncDispatch(first)).andExpect(status().isOk());
        }
        return actions.andExpect(status().isOk());
    }

    private static Packet packet(String id, PacketVisibility visibility) {
        Tossup tossup = Tossup.builder().id(id + "-t").question("Q?").answer("A").build();
        Packet p = Packet.builder().id(id).name("Packet " + id).ownerId("someone").visibility(visibility).build();
        p.setTossups(new ArrayList<>(List.of(ContainsTossup.builder().order(0).tossup(tossup).build())));
        return p;
    }
}
