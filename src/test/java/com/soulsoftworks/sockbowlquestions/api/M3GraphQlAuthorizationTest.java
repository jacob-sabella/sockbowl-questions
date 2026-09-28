package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller;
import com.soulsoftworks.sockbowlquestions.support.M3AuthFixtures;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.ADMIN;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.ANONYMOUS;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.AUTHOR_OTHER;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.AUTHOR_OWNER;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.MODERATOR;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.PLAYER;
import static com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.Caller.SERVICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * PB-21: the GraphQL authorization matrix for every M3 operation, through the real
 * {@code POST /graphql} path with auth on ({@code SecurityConfig}, method security, the
 * GraphQL security exception resolver and {@link AuthoringExceptionResolver}), real
 * services and a real Neo4j ({@link Neo4jContainerTestBase}). Callers are mock JWTs
 * ({@link com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport}) carrying
 * exactly the authorities of each composite role after the M3 D4 move. Nothing is
 * mocked below the controller, so the service-level read gate of clone/export
 * ({@code PacketImportService} + {@code PacketReadPolicy}) and the projection's
 * visibility {@code WHERE} are exercised for real.
 *
 * <p>Every case asserts {@code errors[0].extensions.classification} (or no error), and
 * every denied case, plus every read-only success, also asserts that the fixture graph
 * is byte-for-byte unchanged (no version bump, no clone, no taxonomy write), so no
 * operation can do partial work before it is refused.
 *
 * <h2>Fixtures (fresh per case)</h2>
 * {@code draft} (DRAFT) and {@code pub} (PUBLISHED) are owned by {@code AUTHOR_OWNER};
 * {@code ownerless} is a DRAFT with no owner (D3); {@code eph} is an ownerless EPHEMERAL
 * game-only packet (D15). Each has one tossup and one 3-part bonus. Taxonomy: categories
 * {@code cat} (subcategories {@code sub}, {@code subB}) and {@code cat2} ({@code sub2}),
 * difficulties {@code diff} and {@code diff2}.
 *
 * <h2>Matrix</h2>
 * U = UNAUTHORIZED, F = FORBIDDEN, NF = NOT_FOUND, ok = success.
 * <pre>
 * operation                                 ANON PLAYER OWNER OTHER MOD  ADMIN SERVICE
 * importPacket(dryRun)                      U    F      ok    ok    F    ok    F
 * importPacket(commit)                      U    F      ok    ok    F    ok    F
 * clonePacket(author's DRAFT)               U    F      ok    NF    F    ok    F
 * clonePacket(author's PUBLISHED)           U    F      ok    F     F    ok    F
 * clonePacket(ownerless DRAFT)              U    F      NF    NF    F    ok    F
 * clonePacket(EPHEMERAL)                    U    F      NF    NF    F    NF    F
 * clonePacket(unknown id)                   U    F      NF    NF    F    NF    F
 * exportPacket(author's DRAFT)              NF   NF     ok    NF    NF   ok    ok
 * exportPacket(author's PUBLISHED)          U    F      ok    F     F    ok    ok
 * exportPacket(ownerless DRAFT)             NF   NF     NF    NF    NF   ok    ok
 * exportPacket(EPHEMERAL)                   NF   NF     NF    NF    NF   NF    ok
 * exportPacket(unknown id)                  NF   NF     NF    NF    NF   NF    NF
 * setPacketVisibility(author's DRAFT)       U    F      ok    F     F    ok    F
 * setPacketVisibility(ownerless DRAFT)      U    F      F     F     F    ok    F
 * setPacketVisibility(EPHEMERAL)            U    F      F     F     F    F     F
 * setTossupSubcategory(null, author's)      U    F      ok    F     F    ok    F
 * setTossupSubcategory(null, ownerless)     U    F      F     F     F    ok    F
 * setBonusSubcategory(null, author's)       U    F      ok    F     F    ok    F
 * createCategory                            U    F      F     F     ok   ok    F
 * renameCategory                            U    F      F     F     ok   ok    F
 * renameSubcategory                         U    F      F     F     ok   ok    F
 * renameDifficulty                          U    F      F     F     ok   ok    F
 * mergeCategories                           U    F      F     F     ok   ok    F
 * mergeSubcategories                        U    F      F     F     ok   ok    F
 * mergeDifficulties                         U    F      F     F     ok   ok    F
 * packets(filter: {mine: true})             ok   ok     ok    ok    ok   ok    ok   (anonymous: empty page)
 * </pre>
 * Anonymous {@code exportPacket} of a PUBLISHED packet is UNAUTHORIZED rather than
 * FORBIDDEN: it is visible, so it isn't hidden as NOT_FOUND, and Spring GraphQL
 * classifies an access denial for an anonymous caller as UNAUTHORIZED ("log in").
 *
 * <p>Outside the matrix: {@code packets} visibility per caller (EPHEMERAL never listed,
 * D15, even for admin and the service token), {@code CONFLICT} and
 * {@code VALIDATION_FAILED} reaching the client with their extensions, a stale
 * {@code expectedVersion} never leaking past authorization, and {@code PAYLOAD_TOO_LARGE}
 * for an oversized import (only after authorization).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "sockbowl.auth.enabled=true",
        // Small enough that the oversize case sends a few KB, not 512 KiB.
        "sockbowl.packet.import.max-bytes=" + M3GraphQlAuthorizationTest.IMPORT_MAX_BYTES
})
@AutoConfigureMockMvc
class M3GraphQlAuthorizationTest extends Neo4jContainerTestBase {

    static final int IMPORT_MAX_BYTES = 4096;

    private static final String IMPORT_TEXT = M3AuthFixtures.IMPORT_TEXT;

    @Autowired private MockMvc mvc;
    @Autowired private Neo4jClient neo4j;

    /** Unique per case, so fixtures never collide with other classes sharing the container. */
    private String p;

    @BeforeEach
    void seed() {
        p = M3AuthFixtures.newPrefix();
        M3AuthFixtures.seed(neo4j, p, AUTHOR_OWNER.sub());
    }

    @AfterEach
    void clean() {
        M3AuthFixtures.clean(neo4j, p);
    }

    /* ================================== the matrix ================================== */

    enum Outcome { OK, UNAUTHORIZED, FORBIDDEN, NOT_FOUND }

    /** Checks run on a successful response, with the fixture prefix and the caller. */
    @FunctionalInterface
    interface SuccessCheck {
        void verify(ResultActions result, String prefix, Caller caller) throws Exception;
    }

    /**
     * One M3 operation. {@code query} uses {@code {P}} for the fixture prefix;
     * {@code root} is the response's root field. {@code readOnly} operations must leave the
     * fixture graph unchanged even when they succeed.
     */
    record Op(String label, String root, String query, boolean readOnly, boolean withImportText,
              Map<Caller, Outcome> expected, SuccessCheck onSuccess) {
    }

    /** A named matrix row: one operation, one caller, one expected outcome. */
    record Row(Op op, Caller caller, Outcome expected) {
        @Override
        public String toString() {
            return op.label() + " as " + caller + " -> " + expected;
        }
    }

    private static final Outcome OK = Outcome.OK;
    private static final Outcome U = Outcome.UNAUTHORIZED;
    private static final Outcome F = Outcome.FORBIDDEN;
    private static final Outcome NF = Outcome.NOT_FOUND;

    /** Expected outcomes in {@link Caller} declaration order: ANON, PLAYER, OWNER, OTHER, MOD, ADMIN, SERVICE. */
    private static Map<Caller, Outcome> expect(Outcome... outcomes) {
        Caller[] callers = Caller.values();
        assertThat(outcomes).hasSize(callers.length);
        Map<Caller, Outcome> map = new EnumMap<>(Caller.class);
        for (int i = 0; i < callers.length; i++) {
            map.put(callers[i], outcomes[i]);
        }
        return map;
    }

    private static final SuccessCheck NONE = (r, prefix, caller) -> { };

    /** A clone or import lands as a fresh DRAFT at version 0 owned by the caller. */
    private static SuccessCheck ownedDraft(String path) {
        return (r, prefix, caller) -> r
                .andExpect(jsonPath(path + ".owner.id").value(caller.sub()))
                .andExpect(jsonPath(path + ".visibility").value("DRAFT"))
                .andExpect(jsonPath(path + ".version").value(0))
                .andExpect(jsonPath(path + ".id", not(containsString(prefix))));
    }

    private static SuccessCheck exportContains(String answer) {
        return (r, prefix, caller) -> r.andExpect(jsonPath("$.data.exportPacket", containsString(answer)));
    }

    static List<Op> operations() {
        List<Op> ops = new ArrayList<>();

        // --------------------------------- import (Q3) ---------------------------------
        ops.add(new Op("importPacket(dryRun)", "importPacket",
                "mutation($text: String!) { importPacket(input: {text: $text, dryRun: true}) "
                        + "{ committed packet { id } parsed { tossups { question } bonuses { parts { answer } } } } }",
                true, true, expect(U, F, OK, OK, F, OK, F),
                (r, prefix, caller) -> r
                        .andExpect(jsonPath("$.data.importPacket.committed").value(false))
                        .andExpect(jsonPath("$.data.importPacket.packet").value(nullValue()))
                        .andExpect(jsonPath("$.data.importPacket.parsed.tossups.length()").value(1))
                        .andExpect(jsonPath("$.data.importPacket.parsed.bonuses[0].parts.length()").value(3))));
        ops.add(new Op("importPacket(commit)", "importPacket",
                "mutation($text: String!) { importPacket(input: {text: $text, name: \"{P}imported\", dryRun: false}) "
                        + "{ committed packet { id visibility version owner { id } } } }",
                false, true, expect(U, F, OK, OK, F, OK, F),
                (r, prefix, caller) -> {
                    r.andExpect(jsonPath("$.data.importPacket.committed").value(true));
                    ownedDraft("$.data.importPacket.packet").verify(r, prefix, caller);
                }));

        // ---------------------------------- clone (Q3) ----------------------------------
        String clone = "mutation { clonePacket(id: \"{P}%s\", name: \"{P}clone\") "
                + "{ id visibility version owner { id } tossups { tossup { answer } } } }";
        ops.add(new Op("clonePacket(author's DRAFT)", "clonePacket", clone.formatted("draft"),
                false, false, expect(U, F, OK, NF, F, OK, F), (r, prefix, caller) -> {
                    ownedDraft("$.data.clonePacket").verify(r, prefix, caller);
                    r.andExpect(jsonPath("$.data.clonePacket.tossups[0].tossup.answer").value("Answer-draft"));
                }));
        ops.add(new Op("clonePacket(author's PUBLISHED)", "clonePacket", clone.formatted("pub"),
                false, false, expect(U, F, OK, F, F, OK, F), ownedDraft("$.data.clonePacket")));
        ops.add(new Op("clonePacket(ownerless DRAFT)", "clonePacket", clone.formatted("ownerless"),
                false, false, expect(U, F, NF, NF, F, OK, F), ownedDraft("$.data.clonePacket")));
        ops.add(new Op("clonePacket(EPHEMERAL)", "clonePacket", clone.formatted("eph"),
                false, false, expect(U, F, NF, NF, F, NF, F), NONE));
        ops.add(new Op("clonePacket(unknown id)", "clonePacket", clone.formatted("no-such-packet"),
                false, false, expect(U, F, NF, NF, F, NF, F), NONE));

        // ---------------------------------- export (Q3) ---------------------------------
        String export = "{ exportPacket(id: \"{P}%s\", format: PLAINTEXT) }";
        ops.add(new Op("exportPacket(author's DRAFT)", "exportPacket", export.formatted("draft"),
                true, false, expect(NF, NF, OK, NF, NF, OK, OK), exportContains("Answer-draft")));
        ops.add(new Op("exportPacket(author's PUBLISHED)", "exportPacket", export.formatted("pub"),
                true, false, expect(U, F, OK, F, F, OK, OK), exportContains("BonusAnswer-pub-2")));
        ops.add(new Op("exportPacket(ownerless DRAFT)", "exportPacket", export.formatted("ownerless"),
                true, false, expect(NF, NF, NF, NF, NF, OK, OK), exportContains("Answer-ownerless")));
        ops.add(new Op("exportPacket(EPHEMERAL)", "exportPacket", export.formatted("eph"),
                true, false, expect(NF, NF, NF, NF, NF, NF, OK), exportContains("Answer-eph")));
        ops.add(new Op("exportPacket(unknown id)", "exportPacket", export.formatted("no-such-packet"),
                true, false, expect(NF, NF, NF, NF, NF, NF, NF), NONE));

        // ------------------------- setPacketVisibility (M2 + Q1/Q2) -------------------------
        String publish = "mutation { setPacketVisibility(id: \"{P}%s\", visibility: PUBLISHED, expectedVersion: 0) "
                + "{ id visibility version } }";
        SuccessCheck published = (r, prefix, caller) -> r
                .andExpect(jsonPath("$.data.setPacketVisibility.visibility").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.setPacketVisibility.version").value(1));
        ops.add(new Op("setPacketVisibility(author's DRAFT)", "setPacketVisibility", publish.formatted("draft"),
                false, false, expect(U, F, OK, F, F, OK, F), published));
        ops.add(new Op("setPacketVisibility(ownerless DRAFT)", "setPacketVisibility", publish.formatted("ownerless"),
                false, false, expect(U, F, F, F, F, OK, F), published));
        ops.add(new Op("setPacketVisibility(EPHEMERAL)", "setPacketVisibility", publish.formatted("eph"),
                false, false, expect(U, F, F, F, F, F, F), NONE));

        // ------------------------- subcategory clear (Q2, PB-09) -------------------------
        String clearTossup = "mutation { setTossupSubcategory(tossupId: \"{P}%s-t\", subcategoryId: null, "
                + "expectedVersion: 0) { id subcategory { id } } }";
        SuccessCheck tossupCleared = (r, prefix, caller) ->
                r.andExpect(jsonPath("$.data.setTossupSubcategory.subcategory").value(nullValue()));
        ops.add(new Op("setTossupSubcategory(null, author's)", "setTossupSubcategory", clearTossup.formatted("draft"),
                false, false, expect(U, F, OK, F, F, OK, F), tossupCleared));
        ops.add(new Op("setTossupSubcategory(null, ownerless)", "setTossupSubcategory", clearTossup.formatted("ownerless"),
                false, false, expect(U, F, F, F, F, OK, F), tossupCleared));
        ops.add(new Op("setBonusSubcategory(null, author's)", "setBonusSubcategory",
                "mutation { setBonusSubcategory(bonusId: \"{P}draft-b\", subcategoryId: null) { id subcategory { id } } }",
                false, false, expect(U, F, OK, F, F, OK, F),
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.setBonusSubcategory.subcategory").value(nullValue()))));

        // --------------------------------- taxonomy (Q4, D4) ---------------------------------
        Map<Caller, Outcome> taxonomy = expect(U, F, F, F, OK, OK, F);
        ops.add(new Op("createCategory", "createCategory",
                "mutation { createCategory(name: \"{P}NewCat\") { id name } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.createCategory.name").value(prefix + "NewCat"))));
        ops.add(new Op("renameCategory", "renameCategory",
                "mutation { renameCategory(id: \"{P}cat\", name: \"{P}CatRenamed\") { id name } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.renameCategory.name").value(prefix + "CatRenamed"))));
        ops.add(new Op("renameSubcategory", "renameSubcategory",
                "mutation { renameSubcategory(id: \"{P}sub\", name: \"{P}SubRenamed\") { id name } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.renameSubcategory.name").value(prefix + "SubRenamed"))));
        ops.add(new Op("renameDifficulty", "renameDifficulty",
                "mutation { renameDifficulty(id: \"{P}diff\", name: \"{P}DiffRenamed\") { id name } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.renameDifficulty.name").value(prefix + "DiffRenamed"))));
        ops.add(new Op("mergeCategories", "mergeCategories",
                "mutation { mergeCategories(sourceId: \"{P}cat2\", targetId: \"{P}cat\") { id } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.mergeCategories.id").value(prefix + "cat"))));
        ops.add(new Op("mergeSubcategories", "mergeSubcategories",
                "mutation { mergeSubcategories(sourceId: \"{P}subB\", targetId: \"{P}sub\") { id } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.mergeSubcategories.id").value(prefix + "sub"))));
        ops.add(new Op("mergeDifficulties", "mergeDifficulties",
                "mutation { mergeDifficulties(sourceId: \"{P}diff2\", targetId: \"{P}diff\") { id } }", false, false, taxonomy,
                (r, prefix, caller) -> r.andExpect(jsonPath("$.data.mergeDifficulties.id").value(prefix + "diff"))));

        // --------------------------------- packets list (Q5) ---------------------------------
        ops.add(new Op("packets(filter: {mine: true})", "packets",
                "{ packets(filter: {mine: true, nameContains: \"{P}\"}) { total page size items { id } } }",
                true, false, expect(OK, OK, OK, OK, OK, OK, OK),
                (r, prefix, caller) -> {
                    // Only AUTHOR_OWNER owns fixtures; anonymous gets an empty page, not an error.
                    List<String> mine = caller == AUTHOR_OWNER ? List.of(prefix + "draft", prefix + "pub") : List.of();
                    r.andExpect(jsonPath("$.data.packets.total").value(mine.size()))
                            .andExpect(jsonPath("$.data.packets.page").value(0))
                            .andExpect(jsonPath("$.data.packets.size").value(25))
                            .andExpect(jsonPath("$.data.packets.items[*].id").value(
                                    org.hamcrest.Matchers.containsInAnyOrder(mine.toArray())));
                }));
        return ops;
    }

    static List<Row> matrix() {
        List<Row> rows = new ArrayList<>();
        for (Op op : operations()) {
            for (Caller caller : Caller.values()) {
                rows.add(new Row(op, caller, op.expected().get(caller)));
            }
        }
        return rows;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("matrix")
    void permissionMatrix(Row row) throws Exception {
        Op op = row.op();
        List<String> before = snapshot();

        Map<String, Object> variables = op.withImportText() ? Map.of("text", IMPORT_TEXT) : null;
        ResultActions result = graphql(op.query().replace("{P}", p), variables, row.caller().auth());

        if (row.expected() == Outcome.OK) {
            result.andExpect(jsonPath("$.errors").doesNotExist())
                    .andExpect(jsonPath("$.data." + op.root()).exists());
            op.onSuccess().verify(result, p, row.caller());
            if (op.readOnly()) {
                assertThat(snapshot()).as("a read-only operation changed the graph").isEqualTo(before);
            }
        } else {
            result.andExpect(jsonPath("$.errors[0].extensions.classification").value(row.expected().name()))
                    .andExpect(jsonPath("$.data." + op.root()).doesNotExist());
            assertThat(snapshot()).as("a refused operation changed the graph").isEqualTo(before);
        }
    }

    /** Guards the matrix itself: every M3 operation named in plan 3.1.11 has a row. */
    @Test
    void matrixCoversEveryM3Operation() {
        List<String> roots = operations().stream().map(Op::root).distinct().toList();
        assertThat(roots).contains("packets", "exportPacket", "importPacket", "clonePacket",
                "renameCategory", "renameSubcategory", "renameDifficulty",
                "mergeCategories", "mergeSubcategories", "mergeDifficulties",
                "setPacketVisibility", "createCategory", "setTossupSubcategory");
    }

    /* =================================== packets (Q5) =================================== */

    /**
     * Which fixture packets each caller gets back from {@code packets}. EPHEMERAL is never
     * listed, even for admin (manage-any) and the service token (D15); the ownerless DRAFT
     * only for callers who read every packet.
     */
    @ParameterizedTest(name = "packets visibility as {0}")
    @EnumSource(Caller.class)
    void packetsListsOnlyWhatTheCallerMaySee(Caller caller) throws Exception {
        List<String> expected = switch (caller) {
            case AUTHOR_OWNER -> List.of("draft", "pub");
            case ADMIN, SERVICE -> List.of("draft", "ownerless", "pub");
            default -> List.of("pub");
        };
        graphql("{ packets(filter: {nameContains: \"" + p + "\"}) { total items { id visibility } } }",
                null, caller.auth())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.packets.total").value(expected.size()))
                // Sorted by name, and every fixture's name is prefix + key.
                .andExpect(jsonPath("$.data.packets.items[*].id").value(
                        org.hamcrest.Matchers.contains(expected.stream().map(k -> p + k).toArray())));

        graphql("{ packets(filter: {nameContains: \"" + p + "\", visibility: EPHEMERAL}) { total items { id } } }",
                null, caller.auth())
                .andExpect(jsonPath("$.errors").doesNotExist())
                .andExpect(jsonPath("$.data.packets.total").value(0))
                .andExpect(jsonPath("$.data.packets.items.length()").value(0));
    }

    /* ============================ CONFLICT / VALIDATION_FAILED ============================ */

    @Test
    void staleExpectedVersionIsConflictWithExtensions_forAuthorizedCallersOnly() throws Exception {
        String stale = "mutation { setPacketVisibility(id: \"" + p + "draft\", visibility: PUBLISHED, "
                + "expectedVersion: 7) { id } }";

        // Authorization runs first: a stale version never tells a non-owner anything.
        graphql(stale, null, AUTHOR_OTHER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"))
                .andExpect(jsonPath("$.errors[0].extensions.currentVersion").doesNotExist());
        graphql(stale, null)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"));

        for (Caller allowed : List.of(AUTHOR_OWNER, ADMIN)) {
            graphql(stale, null, allowed.auth())
                    .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"))
                    .andExpect(jsonPath("$.errors[0].extensions.packetId").value(p + "draft"))
                    .andExpect(jsonPath("$.errors[0].extensions.currentVersion").value(0));
        }
        // Node-level mutations resolve and lock the containing packet the same way.
        graphql("mutation { setTossupSubcategory(tossupId: \"" + p + "draft-t\", subcategoryId: null, "
                + "expectedVersion: 3) { id } }", null, AUTHOR_OWNER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("CONFLICT"))
                .andExpect(jsonPath("$.errors[0].extensions.packetId").value(p + "draft"));
        assertThat(version("draft")).isZero();

        graphql(stale.replace("expectedVersion: 7", "expectedVersion: 0"), null, AUTHOR_OWNER.auth())
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(version("draft")).isEqualTo(1L);
    }

    @Test
    void validationFailuresReachTheClientWithTheirField() throws Exception {
        RequestPostProcessor[] moderator = MODERATOR.auth();

        // Rename collision (case-insensitive) names the field and suggests merge.
        graphql("mutation { renameCategory(id: \"" + p + "cat\", name: \"" + (p + "CAT2").toUpperCase() + "\") { id } }",
                null, moderator)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("name"));
        graphql("mutation { mergeCategories(sourceId: \"" + p + "cat\", targetId: \"" + p + "cat\") { id } }",
                null, moderator)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("targetId"));
        graphql("mutation { mergeSubcategories(sourceId: \"" + p + "sub2\", targetId: \"" + p + "sub\") { id } }",
                null, moderator)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("targetId"));

        // Packet content limits (PB-11), for the owner.
        graphql("mutation { renamePacket(id: \"" + p + "draft\", name: \"" + "x".repeat(300) + "\") { id } }",
                null, AUTHOR_OWNER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.errors[0].extensions.field").value("name"));
        // A caller who may not edit the packet gets FORBIDDEN, never the validation detail.
        graphql("mutation { renamePacket(id: \"" + p + "draft\", name: \"" + "x".repeat(300) + "\") { id } }",
                null, AUTHOR_OTHER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        assertThat(version("draft")).isZero();
    }

    /* ================================ PAYLOAD_TOO_LARGE ================================ */

    @Test
    void oversizedImportIsPayloadTooLarge_onlyAfterAuthorization() throws Exception {
        String query = "mutation($text: String!) { importPacket(input: {text: $text, dryRun: true}) { committed } }";
        Map<String, Object> big = Map.of("text", "1. " + "x".repeat(IMPORT_MAX_BYTES) + "\nANSWER: y\n");

        graphql(query, big)
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("UNAUTHORIZED"));
        graphql(query, big, PLAYER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("FORBIDDEN"));
        graphql(query, big, AUTHOR_OWNER.auth())
                .andExpect(jsonPath("$.errors[0].extensions.classification").value("PAYLOAD_TOO_LARGE"))
                .andExpect(jsonPath("$.errors[0].extensions.limitBytes").value(IMPORT_MAX_BYTES));
    }

    /* ==================================== side effects ==================================== */

    @Test
    void cloneIsAnIndependentCopyAndLeavesTheSourceUntouched() throws Exception {
        List<String> before = snapshot(p + "pub");
        String cloneId = com.jayway.jsonpath.JsonPath.read(
                graphql("mutation { clonePacket(id: \"" + p + "pub\") { id name owner { id } } }", null, ADMIN.auth())
                        .andExpect(jsonPath("$.errors").doesNotExist())
                        .andExpect(jsonPath("$.data.clonePacket.name").value(p + "pub (copy)"))
                        .andExpect(jsonPath("$.data.clonePacket.owner.id").value(ADMIN.sub()))
                        .andReturn().getResponse().getContentAsString(),
                "$.data.clonePacket.id");

        // Editing the clone leaves the source packet's subgraph byte-for-byte what it was.
        graphql("mutation { renamePacket(id: \"" + cloneId + "\", name: \"" + p + "clone edited\") { id } }",
                null, ADMIN.auth())
                .andExpect(jsonPath("$.errors").doesNotExist());
        assertThat(snapshot(p + "pub")).isEqualTo(before);
    }

    /* ===================================== helpers ===================================== */

    private ResultActions graphql(String query, Map<String, Object> variables, RequestPostProcessor... auth)
            throws Exception {
        return com.soulsoftworks.sockbowlquestions.support.GraphQlAuthTestSupport.graphql(mvc, query, variables, auth);
    }

    private long version(String key) {
        return neo4j.query("MATCH (pk:Packet {id: $id}) RETURN coalesce(pk.version, 0)")
                .bind(p + key).to("id").fetchAs(Long.class).one().orElseThrow();
    }

    private List<String> snapshot() {
        return M3AuthFixtures.snapshot(neo4j, p);
    }

    private List<String> snapshot(String prefix) {
        return M3AuthFixtures.snapshot(neo4j, prefix);
    }
}
