package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.config.JwtDecoderConfig;
import com.soulsoftworks.sockbowlquestions.config.SecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
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
import com.soulsoftworks.sockbowlquestions.service.PacketAuthoringService;
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

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * D2 over the real {@code POST /graphql} path with auth on: which packets each caller
 * gets back, whether answers are stripped, and who may call {@code setPacketVisibility}.
 * Repositories are mocked; the Cypher filters themselves are covered by
 * {@code PacketRepositoryVisibilityIT} against a real Neo4j.
 */
@WebMvcTest(controllers = {GraphQLController.class, PacketAuthoringController.class},
        properties = "sockbowl.auth.enabled=true")
@ImportAutoConfiguration({GraphQlAutoConfiguration.class, GraphQlWebMvcAutoConfiguration.class,
        GraphQlWebMvcSecurityAutoConfiguration.class})
@Import({SecurityConfig.class, JwtDecoderConfig.class, PacketAuthorizationService.class, PacketReadPolicy.class})
class GraphQlPacketReadAuthTest {

    private static final String OWNER = "owner-sub";
    private static final String FIELDS =
            "id visibility answersRedacted tossups { tossup { question answer } } "
                    + "bonuses { bonus { bonusParts { bonusPart { question answer } } } }";

    @Autowired private MockMvc mvc;

    @MockitoBean private PacketRepository packetRepository;
    @MockitoBean private DifficultyRepository difficultyRepository;
    @MockitoBean private CategoryRepository categoryRepository;
    @MockitoBean private SubcategoryRepository subcategoryRepository;
    @MockitoBean private PacketAuthoringService authoringService;

    private Packet draft;
    private Packet published;
    private Packet legacy;
    private Packet ephemeral;

    @BeforeEach
    void setUp() {
        draft = packet("draft", PacketVisibility.DRAFT);
        published = packet("pub", PacketVisibility.PUBLISHED);
        legacy = packet("legacy", null);
        ephemeral = packet("eph", PacketVisibility.EPHEMERAL);
        ephemeral.setOwnerId(null);
        for (Packet p : List.of(draft, published, legacy, ephemeral)) {
            when(packetRepository.findById(p.getId())).thenReturn(Optional.of(p));
        }
        when(packetRepository.findAll()).thenReturn(List.of(draft, published, legacy, ephemeral));
        // What the unfiltered-by-owner query returns for callers who read every packet.
        // It should leave EPHEMERAL out; the controller re-checks in Java regardless.
        when(packetRepository.findListedPacketIds(any(), any())).thenReturn(List.of("draft", "pub", "legacy", "eph"));
        // What the Cypher filter returns for callers without full-read rights. The
        // controller re-checks in Java, so a draft slipping through must still be dropped.
        when(packetRepository.findVisiblePacketIds(any(), any(), any()))
                .thenReturn(List.of("draft", "pub", "legacy"));
        when(packetRepository.findAllById(anyIterable())).thenReturn(List.of(draft, published, legacy, ephemeral));
        when(packetRepository.searchVisibleByName(any(), any(), any(), any()))
                .thenReturn(List.of(draft, published));
        when(packetRepository.searchByName(any())).thenReturn(List.of(draft, published, ephemeral));
        when(packetRepository.searchListedByName(any(), any(), any())).thenReturn(List.of(draft, published, ephemeral));
    }

    /* ------------------------------ getAllPackets ------------------------------ */

    @Test
    void anonymousGetsOnlyPublicPacketsWithoutAnswers() throws Exception {
        graphql("{ getAllPackets { " + FIELDS + " } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.getAllPackets[*].id", containsInAnyOrder("pub", "legacy")))
                .andExpect(jsonPath("$.data.getAllPackets[*].visibility", everyItemIs("PUBLISHED")))
                .andExpect(jsonPath("$.data.getAllPackets[*].answersRedacted", everyItem(org.hamcrest.Matchers.is(true))))
                .andExpect(jsonPath("$.data.getAllPackets[*].tossups[*].tossup.answer", everyItem(nullValue())))
                .andExpect(jsonPath("$.data.getAllPackets[*].tossups[*].tossup.question",
                        everyItem(org.hamcrest.Matchers.is("Q?"))))
                .andExpect(jsonPath("$.data.getAllPackets[*].bonuses[*].bonus.bonusParts[*].bonusPart.answer",
                        everyItem(nullValue())));
        verify(packetRepository).findVisiblePacketIds(List.of("PUBLISHED"), "PUBLISHED", null);
        verify(packetRepository, never()).findAll();
    }

    @Test
    void ownerSeesOwnDraftWithAnswersAndOthersRedacted() throws Exception {
        graphql("{ getAllPackets { " + FIELDS + " } }", as(OWNER, "packet:create", "packet:update"))
                .andExpect(jsonPath("$.data.getAllPackets[*].id", containsInAnyOrder("draft", "pub", "legacy")))
                // The owner owns all three here, so every answer is present.
                .andExpect(jsonPath("$.data.getAllPackets[*].answersRedacted", everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$.data.getAllPackets[*].tossups[*].tossup.answer", everyItemIs("A")));
        verify(packetRepository).findVisiblePacketIds(List.of("PUBLISHED"), "PUBLISHED", OWNER);
    }

    @Test
    void nonOwnerAuthorDoesNotSeeTheDraft() throws Exception {
        graphql("{ getAllPackets { id answersRedacted } }", as("author2-sub", "packet:create"))
                .andExpect(jsonPath("$.data.getAllPackets[*].id", containsInAnyOrder("pub", "legacy")))
                .andExpect(jsonPath("$.data.getAllPackets[*].answersRedacted", everyItem(org.hamcrest.Matchers.is(true))));
    }

    @Test
    void serviceTokenReadsEveryPacketInFull() throws Exception {
        graphql("{ getAllPackets { " + FIELDS + " } }", as("svc", "packet:read", "packet:read-answers"))
                .andExpect(jsonPath("$.data.getAllPackets[*].id", containsInAnyOrder("draft", "pub", "legacy")))
                .andExpect(jsonPath("$.data.getAllPackets[*].answersRedacted", everyItem(org.hamcrest.Matchers.is(false))))
                .andExpect(jsonPath("$.data.getAllPackets[*].bonuses[*].bonus.bonusParts[*].bonusPart.answer",
                        everyItemIs("BA")));
        // Every listed packet, but never the game-only EPHEMERAL one (D15).
        verify(packetRepository).findListedPacketIds(List.of("EPHEMERAL"), "PUBLISHED");
        verify(packetRepository, never()).findAll();
        verify(packetRepository, never()).findVisiblePacketIds(any(), any(), any());
    }

    /* ------------------------------ getPacketById ------------------------------ */

    @Test
    void anonymousGetPacketByIdOfDraftIsNull() throws Exception {
        graphql("{ getPacketById(id: \"draft\") { id } }")
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.getPacketById").value(nullValue()));
    }

    @Test
    void unknownIdIsIndistinguishableFromAHiddenDraft() throws Exception {
        when(packetRepository.findById("nope")).thenReturn(Optional.empty());
        graphql("{ getPacketById(id: \"nope\") { id } }", as("author2-sub", "packet:create"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.getPacketById").value(nullValue()));
    }

    @Test
    void playerGetsPublishedPacketWithoutAnswers() throws Exception {
        graphql("{ getPacketById(id: \"pub\") { " + FIELDS + " } }", as("player-sub", "game:host"))
                .andExpect(jsonPath("$.data.getPacketById.answersRedacted").value(true))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.question").value("Q?"))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.answer").value(nullValue()));
        // The managed entity was not modified.
        org.assertj.core.api.Assertions.assertThat(published.getTossups().get(0).getTossup().getAnswer()).isEqualTo("A");
    }

    @Test
    void legacyPacketReadsAsPublished() throws Exception {
        graphql("{ getPacketById(id: \"legacy\") { visibility answersRedacted } }")
                .andExpect(jsonPath("$.data.getPacketById.visibility").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.getPacketById.answersRedacted").value(true));
    }

    @Test
    void ownerAndManageAnyAndServiceGetDraftWithAnswers() throws Exception {
        for (RequestPostProcessor who : List.of(as(OWNER, "packet:create"),
                as("admin-sub", "packet:manage-any"),
                as("svc", "packet:read", "packet:read-answers"))) {
            graphql("{ getPacketById(id: \"draft\") { " + FIELDS + " } }", who)
                    .andExpect(jsonPath("$.data.getPacketById.visibility").value("DRAFT"))
                    .andExpect(jsonPath("$.data.getPacketById.answersRedacted").value(false))
                    .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.answer").value("A"));
        }
    }

    /* --------------------------- searchPacketsByName --------------------------- */

    @Test
    void searchFollowsTheSameRules() throws Exception {
        graphql("{ searchPacketsByName(name: \"p\") { id answersRedacted } }")
                // The draft came back from the (mocked) query but is dropped by the Java re-check.
                .andExpect(jsonPath("$.data.searchPacketsByName[*].id", contains("pub")))
                .andExpect(jsonPath("$.data.searchPacketsByName[0].answersRedacted").value(true));
        verify(packetRepository).searchVisibleByName("p", List.of("PUBLISHED"), "PUBLISHED", null);

        graphql("{ searchPacketsByName(name: \"p\") { id answersRedacted } }", as("admin-sub", "packet:manage-any"))
                .andExpect(jsonPath("$.data.searchPacketsByName[*].id", contains("draft", "pub")))
                .andExpect(jsonPath("$.data.searchPacketsByName[*].answersRedacted",
                        everyItem(org.hamcrest.Matchers.is(false))));
        verify(packetRepository).searchListedByName("p", List.of("EPHEMERAL"), "PUBLISHED");
        verify(packetRepository, never()).searchByName(any());
    }

    /* ------------------------------ EPHEMERAL (D15) ------------------------------ */

    @Test
    void ephemeralIsNeverListedOrSearchedForAnyone() throws Exception {
        for (RequestPostProcessor[] who : List.of(new RequestPostProcessor[0],
                new RequestPostProcessor[]{as("player-sub", "game:host")},
                new RequestPostProcessor[]{as("admin-sub", "packet:manage-any")},
                new RequestPostProcessor[]{as("svc", "packet:read", "packet:read-answers")})) {
            graphql("{ getAllPackets { id } }", who)
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(jsonPath("$.data.getAllPackets[*].id", everyItem(org.hamcrest.Matchers.not("eph"))));
            graphql("{ searchPacketsByName(name: \"p\") { id } }", who)
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(jsonPath("$.data.searchPacketsByName[*].id", everyItem(org.hamcrest.Matchers.not("eph"))));
        }
    }

    @Test
    void ephemeralIsReadableInFullOnlyWithTheServiceToken() throws Exception {
        graphql("{ getPacketById(id: \"eph\") { " + FIELDS + " } }", as("svc", "packet:read", "packet:read-answers"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.getPacketById.visibility").value("EPHEMERAL"))
                .andExpect(jsonPath("$.data.getPacketById.answersRedacted").value(false))
                .andExpect(jsonPath("$.data.getPacketById.tossups[0].tossup.answer").value("A"));

        // Not even manage-any, and no one else can tell it exists.
        for (RequestPostProcessor[] who : List.of(new RequestPostProcessor[0],
                new RequestPostProcessor[]{as("player-sub", "game:host")},
                new RequestPostProcessor[]{as("author2-sub", "packet:create", "packet:update")},
                new RequestPostProcessor[]{as("admin-sub", "packet:manage-any", "packet:update")})) {
            graphql("{ getPacketById(id: \"eph\") { id } }", who)
                    .andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(jsonPath("$.data.getPacketById").value(nullValue()));
        }
    }

    @Test
    void ephemeralIsNotEditableEvenWithManageAny() throws Exception {
        String rename = "mutation { renamePacket(id: \"eph\", name: \"x\") { id } }";
        String publish = "mutation { setPacketVisibility(id: \"eph\", visibility: PUBLISHED) { id } }";
        RequestPostProcessor admin = as("admin-sub", "packet:update", "packet:delete", "packet:manage-any");

        graphql(rename, admin).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        graphql(publish, admin).andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService, never()).renamePacket(any(), any());
        verify(authoringService, never()).setPacketVisibility(any(), any());
    }

    @Test
    void ephemeralMayBeDeletedOnlyWithManageAny() throws Exception {
        String delete = "mutation { deletePacket(id: \"eph\") }";
        when(authoringService.deletePacket("eph")).thenReturn(true);

        graphql(delete, as("author-sub", "packet:delete", "packet:update"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService, never()).deletePacket(any());

        graphql(delete, as("admin-sub", "packet:delete", "packet:manage-any"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.deletePacket").value(true));
        verify(authoringService).deletePacket("eph");
    }

    @Test
    void ownerMayStillDeleteOwnDraft() throws Exception {
        when(authoringService.deletePacket("draft")).thenReturn(true);
        graphql("mutation { deletePacket(id: \"draft\") }", as(OWNER, "packet:delete"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        graphql("mutation { deletePacket(id: \"draft\") }", as("author2-sub", "packet:delete"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService).deletePacket("draft");
    }

    /* ---------------------------- setPacketVisibility ---------------------------- */

    private static final String PUBLISH =
            "mutation { setPacketVisibility(id: \"draft\", visibility: PUBLISHED) { id visibility } }";

    @Test
    void setPacketVisibilityAnonymousIsUnauthorized() throws Exception {
        graphql(PUBLISH).andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"));
        verify(authoringService, never()).setPacketVisibility(any(), any());
    }

    @Test
    void setPacketVisibilityWithoutUpdateOrOwnershipIsForbidden() throws Exception {
        graphql(PUBLISH, as("player-sub", "game:host"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        graphql(PUBLISH, as("author2-sub", "packet:update"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        // The service token can read drafts but never change them.
        graphql(PUBLISH, as("svc", "packet:read", "packet:read-answers"))
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        verify(authoringService, never()).setPacketVisibility(any(), any());
    }

    @Test
    void ownerAndManageAnyMayPublish() throws Exception {
        Packet result = packet("draft", PacketVisibility.PUBLISHED);
        when(authoringService.setPacketVisibility("draft", PacketVisibility.PUBLISHED)).thenReturn(result);

        graphql(PUBLISH, as(OWNER, "packet:update"))
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.setPacketVisibility.visibility").value("PUBLISHED"));
        graphql(PUBLISH, as("admin-sub", "packet:update", "packet:manage-any"))
                .andExpect(jsonPath("$.errors").doesNotExist());
        verify(authoringService, org.mockito.Mockito.times(2)).setPacketVisibility(eq("draft"), eq(PacketVisibility.PUBLISHED));
    }

    /* --------------------------------- helpers --------------------------------- */

    private static org.hamcrest.Matcher<Iterable<? extends Object>> everyItemIs(Object value) {
        return everyItem(org.hamcrest.Matchers.is(value));
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

    private static Packet packet(String id, PacketVisibility visibility) {
        Tossup tossup = Tossup.builder().id(id + "-t").question("Q?").answer("A").build();
        BonusPart part = BonusPart.builder().id(id + "-bp").question("BQ?").answer("BA").build();
        Bonus bonus = Bonus.builder().id(id + "-b").preamble("Pre")
                .bonusParts(new ArrayList<>(List.of(new HasBonusPart(0, part)))).build();
        Packet p = Packet.builder().id(id).name("Packet " + id).ownerId(OWNER).visibility(visibility).build();
        p.setTossups(new ArrayList<>(List.of(ContainsTossup.builder().order(0).tossup(tossup).build())));
        p.setBonuses(new ArrayList<>(List.of(new ContainsBonus(0, bonus))));
        return p;
    }
}
