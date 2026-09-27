package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.ImportPacketInput;
import com.soulsoftworks.sockbowlquestions.dto.ImportPacketResultDto;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketImportRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.support.Neo4jContainerTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full-stack (real Neo4j) coverage of {@link PacketImportService} commit, clone and
 * export (plan 3.1.8's {@code PacketImportCypherIT}): the write's counts, orders and
 * relationship directions, that taxonomy is never created, that a clone is an
 * independent copy, and the NOT_FOUND/FORBIDDEN visibility rule for clone/export.
 *
 * <p>Auth is on ({@code sockbowl.auth.enabled=true}) so {@link com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy}
 * actually enforces ownership instead of the auth-off "everyone reads everything"
 * shortcut; callers are built directly as {@link TestingAuthenticationToken}s and
 * pushed onto {@link SecurityContextHolder} (the same pattern as
 * {@code PacketAuthorizationServiceTest}), so no real Keycloak token is needed — the
 * service reads {@link com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy#currentAuthentication()},
 * not a JWT specifically.
 */
@SpringBootTest(properties = "sockbowl.auth.enabled=true")
class PacketImportCypherIT extends Neo4jContainerTestBase {

    private static final String PREFIX = "pic-";

    @Autowired private PacketImportService packetImportService;
    @Autowired private PacketRepository packetRepository;
    @Autowired private CategoryRepository categoryRepository;
    @Autowired private SubcategoryRepository subcategoryRepository;
    @Autowired private Neo4jClient neo4j;
    @Autowired private PacketImportRepository packetImportRepository;

    @BeforeEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void cleanUp() {
        SecurityContextHolder.clearContext();
        neo4j.query("""
                MATCH (p:Packet) WHERE p.id STARTS WITH $prefix OR p.name STARTS WITH $prefix
                OPTIONAL MATCH (p)-[:CONTAINS_TOSSUP]->(t:Tossup)
                OPTIONAL MATCH (p)-[:CONTAINS_BONUS]->(b:Bonus)
                OPTIONAL MATCH (b)-[:HAS_PART]->(bp:BonusPart)
                DETACH DELETE p, t, b, bp
                """).bind(PREFIX).to("prefix").run();
        neo4j.query("""
                MATCH (c:Category) WHERE c.id STARTS WITH $prefix
                OPTIONAL MATCH (c)<-[:SUBCATEGORY_OF]-(s:Subcategory)
                DETACH DELETE c, s
                """).bind(PREFIX).to("prefix").run();
    }

    private void authenticateAs(String sub, String... authorities) {
        Authentication auth = new TestingAuthenticationToken(sub, "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private long countCategories() {
        return neo4j.query("MATCH (c:Category) RETURN count(c) AS c").fetchAs(Long.class).one().orElse(0L);
    }

    private long countSubcategories() {
        return neo4j.query("MATCH (s:Subcategory) RETURN count(s) AS c").fetchAs(Long.class).one().orElse(0L);
    }

    @Test
    void findTaxonomyByKeysResolvesCaseInsensitivelyAndSupportsCategoryOnlyTags() {
        String categoryId = PREFIX + "diag-cat";
        String subcategoryId = PREFIX + "diag-sub";
        neo4j.query("""
                CREATE (c:Category {id: $categoryId, name: 'DiagScience'})
                CREATE (s:Subcategory {id: $subcategoryId, name: 'DiagChem'})
                CREATE (s)-[:SUBCATEGORY_OF]->(c)
                """).bind(categoryId).to("categoryId").bind(subcategoryId).to("subcategoryId").run();

        assertThat(packetImportRepository.findTaxonomyByKeys("DiagScience", "DiagChem"))
                .hasValueSatisfying(s -> {
                    assertThat(s.getName()).isEqualTo("DiagChem");
                    assertThat(s.getCategory().getName()).isEqualTo("DiagScience");
                });
        // Case-insensitive (D5, before Q4's nameKey lands: plain toLower(name) matching).
        assertThat(packetImportRepository.findTaxonomyByKeys("diagscience", "diagchem")).isPresent();
        // A category-only tag whose name doesn't match any same-named subcategory.
        assertThat(packetImportRepository.findTaxonomyByKeys("DiagChem", null)).isEmpty();
        assertThat(packetImportRepository.findTaxonomyByKeys("NoSuchCategory", "NoSuchSub")).isEmpty();

        // A category-only tag DOES resolve when a same-named subcategory exists under it.
        String selfNamedCategoryId = PREFIX + "diag-self-cat";
        String selfNamedSubId = PREFIX + "diag-self-sub";
        neo4j.query("""
                CREATE (c:Category {id: $catId, name: 'DiagSame'})
                CREATE (s:Subcategory {id: $subId, name: 'DiagSame'})
                CREATE (s)-[:SUBCATEGORY_OF]->(c)
                """).bind(selfNamedCategoryId).to("catId").bind(selfNamedSubId).to("subId").run();
        assertThat(packetImportRepository.findTaxonomyByKeys("DiagSame", null))
                .hasValueSatisfying(s -> assertThat(s.getId()).isEqualTo(selfNamedSubId));
    }

    /* ------------------------------------------ commit ----------------------------------------- */

    @Test
    void commitCreatesExpectedGraphNeverCreatesTaxonomyAndLeavesUnknownTagsUncategorized() {
        String categoryId = PREFIX + "cat-science";
        String subcategoryId = PREFIX + "sub-chem";
        neo4j.query("""
                CREATE (c:Category {id: $categoryId, name: 'PicScience'})
                CREATE (s:Subcategory {id: $subcategoryId, name: 'PicChem'})
                CREATE (s)-[:SUBCATEGORY_OF]->(c)
                """).bind(categoryId).to("categoryId").bind(subcategoryId).to("subcategoryId").run();

        long categoriesBefore = countCategories();
        long subcategoriesBefore = countSubcategories();

        String text = """
                pic-commit-test

                TOSSUPS

                1. First question, tagged with an existing subcategory.
                ANSWER: first <PicScience - PicChem>

                2. Second question, with no tag at all.
                ANSWER: second

                3. Third question, tagged with taxonomy that doesn't exist.
                ANSWER: third <PicNoSuchCategory - PicNoSuchSubcat>

                BONUSES

                1. A bonus preamble for ten points each.
                [10] first part
                ANSWER: a
                [10] second part
                ANSWER: b
                [10] third part, tagged.
                ANSWER: c <PicScience - PicChem>
                """;
        authenticateAs("pic-author", "packet:create");
        ImportPacketInput input = new ImportPacketInput(text, null, null, false, false);

        ImportPacketResultDto result = packetImportService.importPacket(input,
                new AuthenticatedUser("pic-author", "Pic Author", Set.of("packet:create"), false));

        assertThat(result.committed()).isTrue();
        assertThat(result.issues()).anyMatch(i -> i.code().equals("UNKNOWN_CATEGORY_TAG"));
        String packetId = result.packet().getId();

        Packet loaded = packetRepository.findById(packetId).orElseThrow();
        assertThat(loaded.getName()).isEqualTo("pic-commit-test");
        assertThat(loaded.getVisibility()).isEqualTo(PacketVisibility.DRAFT);
        assertThat(loaded.getOwnerId()).isEqualTo("pic-author");
        assertThat(loaded.getOwnerDisplayName()).isEqualTo("Pic Author");

        assertThat(loaded.getTossups()).hasSize(3);
        assertThat(loaded.getTossups()).extracting(t -> t.getOrder()).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(loaded.getBonuses()).hasSize(1);
        assertThat(loaded.getBonuses().get(0).getBonus().getBonusParts()).hasSize(3);

        // Node-only properties not mapped on the Packet entity (M4's territory): version and createdVia.
        Map<String, Object> raw = neo4j.query("MATCH (p:Packet {id: $id}) RETURN p.version AS version, p.createdVia AS createdVia")
                .bind(packetId).to("id").fetch().one().orElseThrow();
        assertThat(raw.get("version")).isEqualTo(0L);
        assertThat(raw.get("createdVia")).isEqualTo(PacketImportService.CREATED_VIA_IMPORT);

        // Relationship directions: Tossup -[:SUBCATEGORY_IS]-> Subcategory (OUTGOING),
        // Subcategory -[:SUBCATEGORY_IS]-> Bonus (INCOMING from the bonus's perspective).
        long tossupToSub = neo4j.query("""
                MATCH (p:Packet {id: $id})-[:CONTAINS_TOSSUP]->(t:Tossup {question: 'First question, tagged with an existing subcategory.'})
                MATCH (t)-[:SUBCATEGORY_IS]->(s:Subcategory {id: $subId})
                RETURN count(*) AS c
                """).bind(packetId).to("id").bind(subcategoryId).to("subId")
                .fetchAs(Long.class).one().orElse(0L);
        assertThat(tossupToSub).isEqualTo(1L);

        long subToBonus = neo4j.query("""
                MATCH (p:Packet {id: $id})-[:CONTAINS_BONUS]->(b:Bonus)
                MATCH (s:Subcategory {id: $subId})-[:SUBCATEGORY_IS]->(b)
                RETURN count(*) AS c
                """).bind(packetId).to("id").bind(subcategoryId).to("subId")
                .fetchAs(Long.class).one().orElse(0L);
        assertThat(subToBonus).isEqualTo(1L);

        // The unresolved tag on tossup 3 must not have created anything.
        boolean noPhantomSubcategoryIsAttached = neo4j.query("""
                MATCH (p:Packet {id: $id})-[:CONTAINS_TOSSUP]->(t:Tossup {question: 'Third question, tagged with taxonomy that doesn\\'t exist.'})
                RETURN exists((t)-[:SUBCATEGORY_IS]->(:Subcategory)) AS hasSub
                """).bind(packetId).to("id").fetchAs(Boolean.class).one().orElse(true);
        assertThat(noPhantomSubcategoryIsAttached).isFalse();

        assertThat(countCategories()).isEqualTo(categoriesBefore);
        assertThat(countSubcategories()).isEqualTo(subcategoriesBefore);
    }

    /* ------------------------------------------- clone ------------------------------------------ */

    @Test
    void cloneProducesAnIndependentCopyThatDoesNotAffectTheSource() {
        String sourceId = PREFIX + "clone-src";
        seedSimplePacket(sourceId, "pic-clone-source", "pic-owner", PacketVisibility.DRAFT);

        authenticateAs("pic-owner", "packet:create");
        Packet clone = packetImportService.clonePacket(sourceId, null,
                new AuthenticatedUser("pic-owner", "Pic Owner", Set.of("packet:create"), false));

        assertThat(clone.getId()).isNotEqualTo(sourceId);
        assertThat(clone.getName()).isEqualTo("pic-clone-source (copy)");
        assertThat(clone.getOwnerId()).isEqualTo("pic-owner");
        assertThat(clone.getVisibility()).isEqualTo(PacketVisibility.DRAFT);

        Packet reloadedClone = packetRepository.findById(clone.getId()).orElseThrow();
        String cloneTossupId = reloadedClone.getTossups().get(0).getTossup().getId();
        String sourceTossupId = sourceId + "-t1";
        assertThat(cloneTossupId).isNotEqualTo(sourceTossupId);
        assertThat(reloadedClone.getTossups().get(0).getTossup().getQuestion()).isEqualTo("Source question?");
        assertThat(reloadedClone.getTossups().get(0).getTossup().getAnswer()).isEqualTo("Source answer");

        // Editing the clone must not touch the source.
        neo4j.query("MATCH (t:Tossup {id: $id}) SET t.answer = 'EDITED'").bind(cloneTossupId).to("id").run();
        String sourceAnswerAfterCloneEdit = neo4j.query("MATCH (t:Tossup {id: $id}) RETURN t.answer AS a")
                .bind(sourceTossupId).to("id").fetchAs(String.class).one().orElseThrow();
        assertThat(sourceAnswerAfterCloneEdit).isEqualTo("Source answer");
    }

    @Test
    void cloneAndExportOfAnUnknownIdIsNotFound() {
        authenticateAs("pic-someone", "packet:create");
        AuthenticatedUser user = new AuthenticatedUser("pic-someone", "Pic Someone", Set.of("packet:create"), false);

        assertThatThrownBy(() -> packetImportService.clonePacket(PREFIX + "does-not-exist", null, user))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> packetImportService.exportPacket(PREFIX + "does-not-exist", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cloneAndExportOfAnotherUsersDraftIsNotFound() {
        String draftId = PREFIX + "other-draft";
        seedSimplePacket(draftId, "pic-other-draft", "pic-draft-owner", PacketVisibility.DRAFT);

        authenticateAs("pic-non-owner", "packet:create");
        AuthenticatedUser user = new AuthenticatedUser("pic-non-owner", "Pic Non Owner", Set.of("packet:create"), false);

        assertThatThrownBy(() -> packetImportService.clonePacket(draftId, null, user))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> packetImportService.exportPacket(draftId, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void cloneAndExportOfAnotherUsersPublishedPacketByNonManageAnyAuthorIsForbidden() {
        String publishedId = PREFIX + "other-published";
        seedSimplePacket(publishedId, "pic-other-published", "pic-published-owner", PacketVisibility.PUBLISHED);

        // A non-owner author: holds packet:create (so clonePacket's @PreAuthorize would pass
        // in the real GraphQL layer) but not packet:manage-any, so the service-level
        // read-policy check must be the thing that denies this.
        authenticateAs("pic-other-author", "packet:create");
        AuthenticatedUser user = new AuthenticatedUser("pic-other-author", "Pic Other Author", Set.of("packet:create"), false);

        assertThatThrownBy(() -> packetImportService.clonePacket(publishedId, null, user))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> packetImportService.exportPacket(publishedId, null))
                .isInstanceOf(AccessDeniedException.class);

        // Control: manage-any may still read it in full.
        authenticateAs("pic-admin", "packet:manage-any");
        String exported = packetImportService.exportPacket(publishedId, null);
        assertThat(exported).contains("pic-other-published");
    }

    /* ---------------------------------------- fixture builder -------------------------------------- */

    /** A minimal packet with one tossup and one one-part bonus, for clone/export tests. */
    private void seedSimplePacket(String packetId, String name, String ownerId, PacketVisibility visibility) {
        neo4j.query("""
                CREATE (p:Packet {id: $packetId, name: $name, ownerId: $ownerId, ownerDisplayName: 'Owner',
                                  visibility: $visibility, version: 0, createdVia: 'TEST_SEED'})
                CREATE (t:Tossup {id: $packetId + '-t1', question: 'Source question?', answer: 'Source answer'})
                CREATE (p)-[:CONTAINS_TOSSUP {order: 0}]->(t)
                CREATE (b:Bonus {id: $packetId + '-b1', preamble: 'Source preamble.'})
                CREATE (p)-[:CONTAINS_BONUS {order: 0}]->(b)
                CREATE (bp:BonusPart {id: $packetId + '-bp1', question: 'Part question?', answer: 'Part answer'})
                CREATE (b)-[:HAS_PART {order: 0}]->(bp)
                """)
                .bind(packetId).to("packetId").bind(name).to("name").bind(ownerId).to("ownerId")
                .bind(visibility.name()).to("visibility")
                .run();
    }
}
