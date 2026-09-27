package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.NoSecurityConfig;
import com.soulsoftworks.sockbowlquestions.api.input.PacketFilterInput;
import com.soulsoftworks.sockbowlquestions.dto.PacketPageDto;
import com.soulsoftworks.sockbowlquestions.dto.PacketSummaryDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketSummaryRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code PacketQueryController} in isolation (M3 Q5, plan 3.1.9): a GraphQL slice with
 * {@link PacketSummaryRepository} mocked, proving the controller is a thin pass-through
 * (every filter argument and both page/size reach the repository unchanged, the schema's
 * {@code page = 0, size = 25} defaults apply when they're omitted, and the returned
 * {@link PacketPageDto}/{@link PacketSummaryDto} fields serialize as the {@code PacketPage}
 * type declares). {@code PacketSummaryRepository}'s own Cypher is covered by
 * {@code PacketSummaryCypherIT} against a real Neo4j.
 */
@WebMvcTest(controllers = PacketQueryController.class, properties = "sockbowl.auth.enabled=false")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class})
@Import(NoSecurityConfig.class)
class PacketQueryControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private PacketSummaryRepository packetSummaryRepository;

    @Test
    void everyFilterFieldAndPagingArgumentReachesTheRepository() throws Exception {
        when(packetSummaryRepository.find(any(), any(), any()))
                .thenReturn(new PacketPageDto(List.of(), 0, 2, 10));

        graphql("""
                { packets(filter: {mine: true, nameContains: "abc", difficultyId: "d1",
                                    visibility: PUBLISHED, playableOnly: true}, page: 2, size: 10) {
                    total page size items { id }
                  } }
                """);

        var filterCaptor = org.mockito.ArgumentCaptor.forClass(PacketFilterInput.class);
        verify(packetSummaryRepository).find(filterCaptor.capture(), eq(2), eq(10));
        PacketFilterInput filter = filterCaptor.getValue();
        assertThat(filter.mine()).isTrue();
        assertThat(filter.nameContains()).isEqualTo("abc");
        assertThat(filter.difficultyId()).isEqualTo("d1");
        assertThat(filter.visibility()).isEqualTo(PacketVisibility.PUBLISHED);
        assertThat(filter.playableOnly()).isTrue();
    }

    @Test
    void omittedArgumentsFallBackToTheSchemaDefaults() throws Exception {
        when(packetSummaryRepository.find(any(), any(), any()))
                .thenReturn(new PacketPageDto(List.of(), 0, 0, 25));

        graphql("{ packets { total } }");

        // filter: null (no restriction beyond the caller's read rights); page/size come
        // from the schema's `page: Int = 0, size: Int = 25` defaults, not a Java default.
        verify(packetSummaryRepository).find(isNull(), eq(0), eq(25));
    }

    @Test
    void pageFieldsAndSummaryFieldsSerializeAsDeclared() throws Exception {
        Difficulty difficulty = Difficulty.builder().id("diff-1").name("Hard").build();
        PacketSummaryDto summary = new PacketSummaryDto("pkt-1", "Packet One", difficulty, null,
                PacketVisibility.DRAFT, 3, 2, 1, false);
        when(packetSummaryRepository.find(any(), any(), any()))
                .thenReturn(new PacketPageDto(List.of(summary), 1, 0, 25));

        graphql("""
                { packets { total page size
                    items { id name difficulty { id name } owner { id } visibility version
                            tossupCount bonusCount playable } } }
                """)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.packets.total").value(1))
                .andExpect(jsonPath("$.data.packets.page").value(0))
                .andExpect(jsonPath("$.data.packets.size").value(25))
                .andExpect(jsonPath("$.data.packets.items[0].id").value("pkt-1"))
                .andExpect(jsonPath("$.data.packets.items[0].name").value("Packet One"))
                .andExpect(jsonPath("$.data.packets.items[0].difficulty.id").value("diff-1"))
                .andExpect(jsonPath("$.data.packets.items[0].owner").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.packets.items[0].visibility").value("DRAFT"))
                .andExpect(jsonPath("$.data.packets.items[0].version").value(3))
                .andExpect(jsonPath("$.data.packets.items[0].tossupCount").value(2))
                .andExpect(jsonPath("$.data.packets.items[0].bonusCount").value(1))
                .andExpect(jsonPath("$.data.packets.items[0].playable").value(false));
    }

    private ResultActions graphql(String query) throws Exception {
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query", query));
        ResultActions actions = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body));
        MvcResult first = actions.andReturn();
        if (first.getRequest().isAsyncStarted()) {
            actions = mvc.perform(asyncDispatch(first));
        }
        return actions.andExpect(status().isOk());
    }
}
