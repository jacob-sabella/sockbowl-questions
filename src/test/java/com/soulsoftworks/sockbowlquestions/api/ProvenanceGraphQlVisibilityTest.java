package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.JwtDecoderConfig;
import com.soulsoftworks.sockbowlquestions.config.SecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketAuthorizationService;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.GraphQlAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.security.GraphQlWebMvcSecurityAutoConfiguration;
import org.springframework.boot.graphql.autoconfigure.servlet.GraphQlWebMvcAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D13/M4-PV-01: {@code createdById}/{@code lastModifiedById} over the real
 * {@code POST /graphql} path — null for anonymous callers and non-owners, visible to
 * the owner and {@code packet:manage-any} — for {@code Packet} and, via the owning
 * packet, {@code Tossup}/{@code Bonus}/{@code BonusPart}.
 */
@WebMvcTest(controllers = {GraphQLController.class, ProvenanceFieldResolver.class},
        properties = "sockbowl.auth.enabled=true")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class,
        GraphQlWebMvcSecurityAutoConfiguration.class})
@Import({SecurityConfig.class, JwtDecoderConfig.class, PacketAuthorizationService.class, PacketReadPolicy.class})
class ProvenanceGraphQlVisibilityTest {

    private static final String OWNER = "prov-owner-sub";
    private static final String CREATED_BY = "prov-creator-sub";
    private static final String LAST_MODIFIED_BY = "prov-editor-sub";

    private static final String QUERY =
            "{ getPacketById(id: \"pub\") { id createdById lastModifiedById "
                    + "tossups { tossup { createdById lastModifiedById } } "
                    + "bonuses { bonus { createdById lastModifiedById "
                    + "bonusParts { bonusPart { createdById lastModifiedById } } } } } }";

    @Autowired private MockMvc mvc;

    @MockitoBean private PacketRepository packetRepository;
    @MockitoBean private DifficultyRepository difficultyRepository;
    @MockitoBean private CategoryRepository categoryRepository;
    @MockitoBean private SubcategoryRepository subcategoryRepository;

    private Packet published;

    @BeforeEach
    void setUp() {
        Tossup tossup = Tossup.builder().id("t1").question("Q?").answer("A")
                .source(ContentSource.AUTHORED).createdBy(CREATED_BY).lastModifiedBy(LAST_MODIFIED_BY).build();
        BonusPart part = BonusPart.builder().id("bp1").question("BQ?").answer("BA")
                .source(ContentSource.AUTHORED).createdBy(CREATED_BY).lastModifiedBy(LAST_MODIFIED_BY).build();
        Bonus bonus = Bonus.builder().id("b1").preamble("Pre")
                .source(ContentSource.AUTHORED).createdBy(CREATED_BY).lastModifiedBy(LAST_MODIFIED_BY)
                .bonusParts(new ArrayList<>(List.of(new HasBonusPart(0, part)))).build();
        published = Packet.builder().id("pub").name("Packet pub").ownerId(OWNER)
                .visibility(PacketVisibility.PUBLISHED)
                .source(ContentSource.AUTHORED).createdBy(CREATED_BY).lastModifiedBy(LAST_MODIFIED_BY).build();
        published.setTossups(new ArrayList<>(List.of(ContainsTossup.builder().order(0).tossup(tossup).build())));
        published.setBonuses(new ArrayList<>(List.of(new ContainsBonus(0, bonus))));

        when(packetRepository.findById("pub")).thenReturn(Optional.of(published));
        when(packetRepository.findByTossupId("t1")).thenReturn(Optional.of(published));
        when(packetRepository.findByBonusId("b1")).thenReturn(Optional.of(published));
        when(packetRepository.findByBonusPartId("bp1")).thenReturn(Optional.of(published));
    }

    @Test
    void anonymousGetsNullCreatedByAndLastModifiedBy() throws Exception {
        graphql(QUERY)
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.getPacketById.createdById").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.lastModifiedById").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.createdById").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.bonuses[0].bonus.createdById").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.bonuses[0].bonus.bonusParts[0].bonusPart.createdById")
                        .value(nullValue()));
    }

    @Test
    void nonOwnerGetsNullCreatedByAndLastModifiedBy() throws Exception {
        graphql(QUERY, as("some-other-sub", "packet:create"))
                .andExpect(jsonPath("$.data.getPacketById.createdById").value(nullValue()))
                .andExpect(jsonPath("$.data.getPacketById.lastModifiedById").value(nullValue()));
    }

    @Test
    void ownerSeesCreatedByAndLastModifiedBy() throws Exception {
        graphql(QUERY, as(OWNER, "packet:create"))
                .andExpect(jsonPath("$.data.getPacketById.createdById").value(CREATED_BY))
                .andExpect(jsonPath("$.data.getPacketById.lastModifiedById").value(LAST_MODIFIED_BY))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.createdById").value(CREATED_BY))
                .andExpect(jsonPath("$.data.getPacketById.bonuses[0].bonus.createdById").value(CREATED_BY))
                .andExpect(jsonPath("$.data.getPacketById.bonuses[0].bonus.bonusParts[0].bonusPart.createdById")
                        .value(CREATED_BY));
    }

    @Test
    void manageAnySeesCreatedByAndLastModifiedBy() throws Exception {
        graphql(QUERY, as("admin-sub", "packet:manage-any"))
                .andExpect(jsonPath("$.data.getPacketById.createdById").value(CREATED_BY))
                .andExpect(jsonPath("$.data.getPacketById.lastModifiedById").value(LAST_MODIFIED_BY));
    }

    private ResultActions graphql(String query, RequestPostProcessor... auth) throws Exception {
        String body = new tools.jackson.databind.ObjectMapper().writeValueAsString(Map.of("query", query));
        var req = post("/graphql").contentType(MediaType.APPLICATION_JSON).content(body);
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

    private static RequestPostProcessor as(String sub, String... authorities) {
        return jwt().jwt(j -> j.subject(sub)).authorities(
                Arrays.stream(authorities).map(a -> (GrantedAuthority) new SimpleGrantedAuthority(a)).toList());
    }
}
