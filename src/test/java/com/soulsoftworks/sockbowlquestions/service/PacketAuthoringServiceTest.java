package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.GenerateTossupInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.PacketVersionConflictException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.exception.ValidationFailedException;
import com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.ContentSource;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.quota.ContentQuotaGuard;
import com.soulsoftworks.sockbowlquestions.repository.BonusPartRepository;
import com.soulsoftworks.sockbowlquestions.repository.BonusRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.TossupRepository;
import com.soulsoftworks.sockbowlquestions.security.SecurityAuditorAware;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PacketAuthoringServiceTest {

    @Mock private PacketRepository packetRepository;
    @Mock private TossupRepository tossupRepository;
    @Mock private BonusRepository bonusRepository;
    @Mock private BonusPartRepository bonusPartRepository;
    @Mock private DifficultyRepository difficultyRepository;
    @Mock private SubcategoryRepository subcategoryRepository;
    @Mock private QuestionGenerationService questionGenerationService;
    @Mock private ContentQuotaGuard contentQuotaGuard;
    @Mock private SecurityAuditorAware securityAuditorAware;

    private AiSecurityProperties aiSecurityProperties;
    private PacketLimitsProperties limits;
    private PacketAuthoringService service;

    @BeforeEach
    void setUp() {
        aiSecurityProperties = new AiSecurityProperties();
        limits = new PacketLimitsProperties();
        service = new PacketAuthoringService(packetRepository, tossupRepository, bonusRepository,
                bonusPartRepository, difficultyRepository, subcategoryRepository,
                questionGenerationService, aiSecurityProperties, contentQuotaGuard,
                new PacketValidator(limits), securityAuditorAware);
        // The version bump (PB-18) succeeds unless a test says otherwise.
        lenient().when(packetRepository.bumpVersion(anyString(), any(), any(), any())).thenReturn(1L);
        // Most paths save then return the saved entity; echo the argument back.
        lenient().when(packetRepository.save(any(Packet.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bonusRepository.save(any(Bonus.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tossupRepository.save(any(Tossup.class))).thenAnswer(inv -> inv.getArgument(0));
        // createPacket runs under the packets-owned lock (Q-V1-01); just run the supplier.
        // ownerId may be null (anonymous caller), so match it with any(), not anyString().
        lenient().when(contentQuotaGuard.withPacketsOwnedSlot(any(), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());
    }

    private Packet packetWithId(String id) {
        Packet packet = Packet.builder().name("Test Packet").build();
        packet.setId(id);
        packet.setTossups(new ArrayList<>());
        packet.setBonuses(new ArrayList<>());
        return packet;
    }

    private Tossup tossup(String id, String q) {
        Tossup t = Tossup.builder().question(q).answer("a").build();
        t.setId(id);
        return t;
    }

    private List<Integer> tossupOrders(Packet packet) {
        return packet.getTossups().stream().map(ContainsTossup::getOrder).toList();
    }

    private List<String> tossupIdsInOrder(Packet packet) {
        return packet.getTossups().stream()
                .sorted((a, b) -> a.getOrder() - b.getOrder())
                .map(r -> r.getTossup().getId())
                .toList();
    }

    /* ------------------------------- Packet -------------------------------- */

    @Test
    void createPacket_persistsName() {
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null), "sub-1", "author1");
        assertThat(result.getName()).isEqualTo("My Packet");
        verify(packetRepository).save(any(Packet.class));
    }

    @Test
    void createPacket_capturesOwnerFromCaller() {
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null), "sub-1", "author1");
        assertThat(result.getOwnerId()).isEqualTo("sub-1");
        assertThat(result.getOwnerDisplayName()).isEqualTo("author1");
    }

    @Test
    void createPacket_anonymousCaller_leavesOwnerNull() {
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null), null, null);
        assertThat(result.getOwnerId()).isNull();
        assertThat(result.getOwnerDisplayName()).isNull();
    }

    @Test
    void createPacket_defaultsToDraft() {
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null), "sub-1", "author1");
        assertThat(result.getVisibility()).isEqualTo(PacketVisibility.DRAFT);
    }

    @Test
    void createPacket_setsAuthoredSource() {
        // D13, M4-PV-01: createdBy/createdAt are populated by real SDN auditing
        // (@EnableNeo4jAuditing), which this pure-Mockito test can't exercise; that's
        // covered by ProvenanceAuditingIT against a real Neo4j. This only covers the
        // explicit `source` field, which the service sets itself.
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null), "sub-1", "author1");
        assertThat(result.getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void setPacketVisibility_updatesAndSaves() {
        Packet packet = packetWithId("p1");
        packet.setVisibility(PacketVisibility.DRAFT);
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packet));

        Packet result = service.setPacketVisibility("p1", PacketVisibility.PUBLISHED, null);

        assertThat(result.getVisibility()).isEqualTo(PacketVisibility.PUBLISHED);
        verify(packetRepository).save(packet);
    }

    @Test
    void setPacketVisibility_missingPacket_throwsNotFound() {
        when(packetRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.setPacketVisibility("nope", PacketVisibility.PUBLISHED, null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void setPacketVisibility_null_isRejected() {
        assertThatThrownBy(() -> service.setPacketVisibility("p1", null, null))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(packetRepository, never()).save(any(Packet.class));
    }

    @Test
    void setPacketVisibility_ephemeral_isRejected() {
        // D15: EPHEMERAL only comes from import-random for callers without packet:create.
        assertThatThrownBy(() -> service.setPacketVisibility("p1", PacketVisibility.EPHEMERAL, null))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(packetRepository, never()).save(any(Packet.class));
    }

    @Test
    void createPacket_blankName_throws() {
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput("  ", null), "sub-1", "author1"))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(packetRepository, never()).save(any());
    }

    @Test
    void createPacket_withDifficulty_resolvesReference() {
        Difficulty d = new Difficulty();
        d.setId("d1");
        when(difficultyRepository.findById("d1")).thenReturn(Optional.of(d));
        Packet result = service.createPacket(new CreatePacketInput("P", "d1"), "sub-1", "author1");
        assertThat(result.getDifficulty()).isSameAs(d);
    }

    @Test
    void createPacket_withMissingDifficulty_throwsNotFound() {
        when(difficultyRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput("P", "nope"), "sub-1", "author1"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renamePacket_missing_throwsNotFound() {
        when(packetRepository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.renamePacket("x", "New", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletePacket_missing_throwsNotFound() {
        when(packetRepository.existsById("x")).thenReturn(false);
        assertThatThrownBy(() -> service.deletePacket("x", null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(packetRepository, never()).deletePacketCascade(anyString());
    }

    @Test
    void deletePacket_present_cascadesToOwnedQuestions() {
        when(packetRepository.existsById("p")).thenReturn(true);
        assertThat(service.deletePacket("p", null)).isTrue();
        // Packet questions are packet-owned (copy-on-generate), so delete must
        // cascade to them instead of leaving orphan tossup/bonus/part nodes.
        verify(packetRepository).deletePacketCascade("p");
    }

    /* ------------------------------- Tossups ------------------------------- */

    @Test
    void addTossup_appendsAndOrders() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        service.addTossupToPacket("p", new TossupInput("Q1", "A1", null), null, null);
        Packet result = service.addTossupToPacket("p", new TossupInput("Q2", "A2", null), null, null);

        assertThat(tossupOrders(result)).containsExactly(0, 1);
        assertThat(result.getTossups().get(1).getTossup().getQuestion()).isEqualTo("Q2");
        // D13, M4-PV-01
        assertThat(result.getTossups().get(1).getTossup().getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void addTossup_atIndexZero_shiftsExisting() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.addTossupToPacket("p", new TossupInput("Q0", "A0", null), 0, null);

        assertThat(tossupIdsInOrder(result)).hasSize(2);
        assertThat(result.getTossups().stream()
                .filter(r -> r.getOrder() == 0).findFirst().get().getTossup().getQuestion())
                .isEqualTo("Q0");
        assertThat(tossupOrders(result)).containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void addTossup_blankQuestion_throws() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        assertThatThrownBy(() -> service.addTossupToPacket("p", new TossupInput("", "A", null), null, null))
                .isInstanceOf(InvalidApiRequestException.class);
    }

    @Test
    void updateTossup_setsFields() {
        Tossup t = tossup("t1", "old");
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        Tossup result = service.updateTossup("t1", new TossupInput("new", "ans", null), null);
        assertThat(result.getQuestion()).isEqualTo("new");
        assertThat(result.getAnswer()).isEqualTo("ans");
    }

    @Test
    void updateTossup_marksAuthoredEvenIfPreviouslyImportedOrGenerated() {
        // D13, M4-PV-01: a manual edit makes the content AUTHORED from here on,
        // whatever it started as.
        Tossup t = tossup("t1", "old");
        t.setSource(ContentSource.QBREADER_IMPORT);
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        Tossup result = service.updateTossup("t1", new TossupInput("new", "ans", null), null);
        assertThat(result.getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void removeTossup_renumbersRemaining() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        packet.getTossups().add(ContainsTossup.builder().order(2).tossup(tossup("t3", "Q3")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.removeTossupFromPacket("p", "t2", null);

        assertThat(tossupIdsInOrder(result)).containsExactly("t1", "t3");
        assertThat(tossupOrders(result)).containsExactly(0, 1);
        verify(tossupRepository).deleteById("t2");
    }

    @Test
    void removeTossup_notInPacket_throwsNotFound() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        assertThatThrownBy(() -> service.removeTossupFromPacket("p", "ghost", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reorderTossup_movesAndRenumbers() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        packet.getTossups().add(ContainsTossup.builder().order(2).tossup(tossup("t3", "Q3")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.reorderTossup("p", "t3", 0, null);

        assertThat(tossupIdsInOrder(result)).containsExactly("t3", "t1", "t2");
        assertThat(tossupOrders(result)).containsExactly(0, 1, 2);
    }

    @Test
    void reorderTossup_oversizedNewOrder_clampsToEnd() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.reorderTossup("p", "t1", 99, null);

        assertThat(tossupIdsInOrder(result)).containsExactly("t2", "t1");
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @Test
    void addBonus_withParts_ordersParts() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        BonusInput input = new BonusInput("Preamble", null,
                List.of(new BonusPartInput("BQ1", "BA1"), new BonusPartInput("BQ2", "BA2")));
        Packet result = service.addBonusToPacket("p", input, null, null);

        assertThat(result.getBonuses()).hasSize(1);
        ContainsBonus cb = result.getBonuses().get(0);
        assertThat(cb.getOrder()).isZero();
        assertThat(cb.getBonus().getBonusParts()).hasSize(2);
        assertThat(cb.getBonus().getBonusParts().get(0).getOrder()).isZero();
        assertThat(cb.getBonus().getBonusParts().get(1).getOrder()).isEqualTo(1);
        // D13, M4-PV-01: the bonus and every part it was created with are AUTHORED.
        assertThat(cb.getBonus().getSource()).isEqualTo(ContentSource.AUTHORED);
        assertThat(cb.getBonus().getBonusParts().get(0).getBonusPart().getSource())
                .isEqualTo(ContentSource.AUTHORED);
        assertThat(cb.getBonus().getBonusParts().get(1).getBonusPart().getSource())
                .isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void addBonus_secondBonus_getsNextOrder() {
        Packet packet = packetWithId("p");
        Bonus existing = new Bonus();
        existing.setId("b1");
        packet.getBonuses().add(new ContainsBonus(0, existing));
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        // M3 (PB-11): a new bonus needs at least one part.
        Packet result = service.addBonusToPacket("p",
                new BonusInput("P2", null, List.of(new BonusPartInput("BQ", "BA"))), null, null);

        assertThat(result.getBonuses()).hasSize(2);
        assertThat(result.getBonuses().stream().map(ContainsBonus::getOrder))
                .containsExactlyInAnyOrder(0, 1);
    }

    @Test
    void removeBonus_renumbersAndDeletes() {
        Packet packet = packetWithId("p");
        Bonus b1 = new Bonus();
        b1.setId("b1");
        Bonus b2 = new Bonus();
        b2.setId("b2");
        packet.getBonuses().add(new ContainsBonus(0, b1));
        packet.getBonuses().add(new ContainsBonus(1, b2));
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.removeBonusFromPacket("p", "b1", null);

        assertThat(result.getBonuses()).hasSize(1);
        assertThat(result.getBonuses().get(0).getOrder()).isZero();
        assertThat(result.getBonuses().get(0).getBonus().getId()).isEqualTo("b2");
        verify(bonusRepository).deleteById("b1");
    }

    @Test
    void updateBonus_missing_throwsNotFound() {
        when(bonusRepository.findById("b")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateBonus("b",
                new com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput("x", null), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateBonus_marksAuthoredEvenIfPreviouslyImportedOrGenerated() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setSource(ContentSource.AI_GENERATED);
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.updateBonus("b1",
                new com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput("New preamble", null), null);

        assertThat(result.getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @Test
    void addBonusPart_appendsWithOrder() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(0, partWithId("bp1")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.addBonusPart("b1", new BonusPartInput("Q", "A"), null, null);

        assertThat(result.getBonusParts()).hasSize(2);
        assertThat(result.getBonusParts().get(1).getOrder()).isEqualTo(1);
        // D13, M4-PV-01
        assertThat(result.getBonusParts().get(1).getBonusPart().getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void updateBonusPart_marksAuthoredEvenIfPreviouslyImportedOrGenerated() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        BonusPart part = partWithId("bp1");
        part.setSource(ContentSource.QBREADER_IMPORT);
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(0, part))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.updateBonusPart("b1", "bp1", new BonusPartInput("New Q", "New A"), null);

        BonusPart updated = result.getBonusParts().get(0).getBonusPart();
        assertThat(updated.getQuestion()).isEqualTo("New Q");
        assertThat(updated.getSource()).isEqualTo(ContentSource.AUTHORED);
    }

    @Test
    void removeBonusPart_renumbersAndDeletes() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(
                new HasBonusPart(0, partWithId("bp1")),
                new HasBonusPart(1, partWithId("bp2")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.removeBonusPart("b1", "bp1", null);

        assertThat(result.getBonusParts()).hasSize(1);
        assertThat(result.getBonusParts().get(0).getOrder()).isZero();
        assertThat(result.getBonusParts().get(0).getBonusPart().getId()).isEqualTo("bp2");
        verify(bonusPartRepository).deleteById("bp1");
    }

    @Test
    void reorderBonusPart_moves() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(
                new HasBonusPart(0, partWithId("bp1")),
                new HasBonusPart(1, partWithId("bp2")),
                new HasBonusPart(2, partWithId("bp3")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.reorderBonusPart("b1", "bp3", 0, null);

        assertThat(result.getBonusParts().stream().map(r -> r.getBonusPart().getId()))
                .containsExactly("bp3", "bp1", "bp2");
        assertThat(result.getBonusParts().stream().map(HasBonusPart::getOrder))
                .containsExactly(0, 1, 2);
    }

    private BonusPart partWithId(String id) {
        BonusPart p = new BonusPart();
        p.setId(id);
        p.setQuestion("q");
        p.setAnswer("a");
        return p;
    }

    /* ------------------------------- AI assist ----------------------------- */

    @Test
    void generateAndAddTossup_requiresApiKeyWhenSecured() {
        aiSecurityProperties.setRequireUserApiKey(true);
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        GenerateTossupInput input = new GenerateTossupInput("Science", null, null, null, null);
        assertThatThrownBy(() -> service.generateAndAddTossup("p", input, null, null))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(questionGenerationService, never()).generateTossup(any(), any(), any(), anyList(), any());
    }

    @Test
    void generateAndAddTossup_addsGeneratedTossup() {
        aiSecurityProperties.setRequireUserApiKey(false);
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        Tossup generated = tossup("gen", "Generated?");
        // D13, M4-PV-01: the generation strategy is responsible for stamping
        // source/aiModel; this service must pass them through unchanged.
        generated.setSource(ContentSource.AI_GENERATED);
        generated.setAiModel("test-model");
        when(questionGenerationService.generateTossup(anyString(), any(), any(), anyList(), any(AiRequestContext.class)))
                .thenReturn(generated);

        GenerateTossupInput input = new GenerateTossupInput("Science", "context", null, null, null);
        Packet result = service.generateAndAddTossup("p", input, null, null);

        assertThat(result.getTossups()).hasSize(1);
        assertThat(result.getTossups().get(0).getTossup().getQuestion()).isEqualTo("Generated?");
        assertThat(result.getTossups().get(0).getOrder()).isZero();
        assertThat(result.getTossups().get(0).getTossup().getSource()).isEqualTo(ContentSource.AI_GENERATED);
        assertThat(result.getTossups().get(0).getTossup().getAiModel()).isEqualTo("test-model");
    }

    /* ------------------------ M3 Q2: field limits (PB-11) ------------------------ */

    private static String chars(int n) {
        return "x".repeat(n);
    }

    @Test
    void createPacket_overLongName_isRejectedWithField() {
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput(chars(201), null), "sub-1", "a"))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("name"));
        verify(packetRepository, never()).save(any());
    }

    @Test
    void createPacket_nameAtTheLimit_isAccepted() {
        Packet result = service.createPacket(new CreatePacketInput(chars(200), null), "sub-1", "a");
        assertThat(result.getName()).hasSize(200);
        assertThat(result.getVersion()).isZero();
    }

    @Test
    void renamePacket_overLongName_isRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.renamePacket("p", chars(201), null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("name"));
        verify(packetRepository, never()).bumpVersion(anyString(), any(), any(), any());
        verify(packetRepository, never()).save(any());
    }

    @Test
    void addTossup_overLongQuestion_isRejected() {
        when(packetRepository.findById("p")).thenReturn(Optional.of(packetWithId("p")));
        assertThatThrownBy(() -> service.addTossupToPacket("p", new TossupInput(chars(4001), "A", null), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("question"));
        verify(packetRepository, never()).save(any());
    }

    @Test
    void addTossup_overLongAnswer_isRejected() {
        when(packetRepository.findById("p")).thenReturn(Optional.of(packetWithId("p")));
        assertThatThrownBy(() -> service.addTossupToPacket("p", new TossupInput("Q", chars(1001), null), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("answer"));
    }

    @Test
    void updateTossup_overLongQuestion_isRejected() {
        assertThatThrownBy(() -> service.updateTossup("t1", new TossupInput(chars(4001), "A", null), null))
                .isInstanceOf(ValidationFailedException.class);
        verify(tossupRepository, never()).save(any());
    }

    @Test
    void addBonus_overLongPreamble_isRejected() {
        BonusInput input = new BonusInput(chars(2001), null, List.of(new BonusPartInput("Q", "A")));
        assertThatThrownBy(() -> service.addBonusToPacket("p", input, null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("preamble"));
    }

    @Test
    void updateBonus_overLongPreamble_isRejected() {
        assertThatThrownBy(() -> service.updateBonus("b1", new BonusUpdateInput(chars(2001), null), null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("preamble"));
        verify(bonusRepository, never()).save(any());
    }

    @Test
    void updateBonusPart_overLongAnswer_isRejected() {
        assertThatThrownBy(() -> service.updateBonusPart("b1", "bp1", new BonusPartInput("Q", chars(1001)), null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("answer"));
    }

    @Test
    void limitsComeFromConfiguration() {
        limits.getLimits().setNameMax(5);
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput("Sixsix", null), "sub-1", "a"))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessageContaining("5 characters");
    }

    /* -------------------- M3 Q2: structural limits (PB-11) --------------------- */

    private Packet packetWithTossups(String id, int count) {
        Packet packet = packetWithId(id);
        for (int i = 0; i < count; i++) {
            packet.getTossups().add(ContainsTossup.builder().order(i).tossup(tossup("t" + i, "Q" + i)).build());
        }
        return packet;
    }

    @Test
    void addTossup_61st_isRejected() {
        when(packetRepository.findById("p")).thenReturn(Optional.of(packetWithTossups("p", 60)));
        assertThatThrownBy(() -> service.addTossupToPacket("p", new TossupInput("Q", "A", null), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("tossups"))
                .hasMessageContaining("60");
        verify(packetRepository, never()).save(any());
    }

    @Test
    void addTossup_60th_isAccepted() {
        when(packetRepository.findById("p")).thenReturn(Optional.of(packetWithTossups("p", 59)));
        Packet result = service.addTossupToPacket("p", new TossupInput("Q", "A", null), null, null);
        assertThat(result.getTossups()).hasSize(60);
    }

    @Test
    void generateAndAddTossup_atTheCap_isRejectedBeforeCallingTheModel() {
        aiSecurityProperties.setRequireUserApiKey(false);
        when(packetRepository.findById("p")).thenReturn(Optional.of(packetWithTossups("p", 60)));
        assertThatThrownBy(() -> service.generateAndAddTossup("p",
                new GenerateTossupInput("Science", null, null, null, null), null, null))
                .isInstanceOf(ValidationFailedException.class);
        verify(questionGenerationService, never()).generateTossup(any(), any(), any(), anyList(), any());
    }

    @Test
    void addBonus_61st_isRejected() {
        Packet packet = packetWithId("p");
        for (int i = 0; i < 60; i++) {
            Bonus b = new Bonus();
            b.setId("b" + i);
            packet.getBonuses().add(new ContainsBonus(i, b));
        }
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        BonusInput input = new BonusInput("P", null, List.of(new BonusPartInput("Q", "A")));
        assertThatThrownBy(() -> service.addBonusToPacket("p", input, null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("bonuses"));
    }

    @Test
    void addBonus_withNullParts_isRejected() {
        assertThatThrownBy(() -> service.addBonusToPacket("p", new BonusInput("P", null, null), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("parts"))
                .hasMessage("A bonus needs at least one part");
        verify(packetRepository, never()).bumpVersion(anyString(), any(), any(), any());
        verify(packetRepository, never()).save(any());
    }

    @Test
    void addBonus_withEmptyParts_isRejected() {
        assertThatThrownBy(() -> service.addBonusToPacket("p", new BonusInput("P", null, List.of()), null, null))
                .isInstanceOf(ValidationFailedException.class)
                .hasMessage("A bonus needs at least one part");
    }

    @Test
    void addBonus_withTooManyParts_isRejected() {
        List<BonusPartInput> seven = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            seven.add(new BonusPartInput("Q" + i, "A" + i));
        }
        assertThatThrownBy(() -> service.addBonusToPacket("p", new BonusInput("P", null, seven), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("parts"));
    }

    @Test
    void addBonusPart_seventh_isRejected() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        List<HasBonusPart> parts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            parts.add(new HasBonusPart(i, partWithId("bp" + i)));
        }
        bonus.setBonusParts(parts);
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));
        assertThatThrownBy(() -> service.addBonusPart("b1", new BonusPartInput("Q", "A"), null, null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("parts"));
        verify(bonusRepository, never()).save(any());
    }

    @Test
    void removeBonusPart_lastPart_isRejected() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(0, partWithId("bp1")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        assertThatThrownBy(() -> service.removeBonusPart("b1", "bp1", null))
                .isInstanceOfSatisfying(ValidationFailedException.class,
                        e -> assertThat(e.getField()).isEqualTo("parts"))
                .hasMessage("A bonus needs at least one part; delete the bonus instead");
        verify(bonusPartRepository, never()).deleteById(anyString());
        verify(bonusRepository, never()).save(any());
    }

    @Test
    void removeBonusPart_unknownPart_isStillNotFound() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(0, partWithId("bp1")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));
        assertThatThrownBy(() -> service.removeBonusPart("b1", "ghost", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ---------------------- M3 Q2: subcategory clear (PB-09) ---------------------- */

    @Test
    void setTossupSubcategory_null_clearsIt() {
        Tossup t = tossup("t1", "Q");
        t.setSubcategory(new Subcategory());
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));

        Tossup result = service.setTossupSubcategory("t1", null, null);

        assertThat(result.getSubcategory()).isNull();
        verify(tossupRepository).clearSubcategory("t1");
        verify(subcategoryRepository, never()).findById(anyString());
        verify(tossupRepository).save(t);
    }

    @Test
    void setTossupSubcategory_blank_clearsIt() {
        Tossup t = tossup("t1", "Q");
        t.setSubcategory(new Subcategory());
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        assertThat(service.setTossupSubcategory("t1", "  ", null).getSubcategory()).isNull();
        verify(tossupRepository).clearSubcategory("t1");
    }

    @Test
    void setTossupSubcategory_id_setsIt() {
        Tossup t = tossup("t1", "Q");
        Subcategory sub = new Subcategory();
        sub.setId("s1");
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        when(subcategoryRepository.findById("s1")).thenReturn(Optional.of(sub));
        assertThat(service.setTossupSubcategory("t1", "s1", null).getSubcategory()).isSameAs(sub);
        verify(tossupRepository, never()).clearSubcategory(anyString());
    }

    @Test
    void setBonusSubcategory_null_clearsIt() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setSubcategory(new Subcategory());
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.setBonusSubcategory("b1", null, null);

        assertThat(result.getSubcategory()).isNull();
        verify(bonusRepository).clearSubcategory("b1");
        verify(bonusRepository).save(bonus);
    }

    /* -------------------- M3 Q2: optimistic locking (PB-18) -------------------- */

    @Test
    void expectedVersionMismatch_throwsConflictWithCurrentVersion() {
        when(packetRepository.bumpVersion(eq("p"), eq(3L), any(), any())).thenReturn(null);
        when(packetRepository.currentVersion("p")).thenReturn(Optional.of(5L));

        assertThatThrownBy(() -> service.renamePacket("p", "New", 3))
                .isInstanceOfSatisfying(PacketVersionConflictException.class, e -> {
                    assertThat(e.getPacketId()).isEqualTo("p");
                    assertThat(e.getExpectedVersion()).isEqualTo(3L);
                    assertThat(e.getCurrentVersion()).isEqualTo(5L);
                });
        verify(packetRepository, never()).findById(anyString());
        verify(packetRepository, never()).save(any());
    }

    @Test
    void bumpOnMissingPacket_throwsNotFound() {
        when(packetRepository.bumpVersion(eq("gone"), eq(1L), any(), any())).thenReturn(null);
        when(packetRepository.currentVersion("gone")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reorderTossup("gone", "t1", 0, 1))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void nullExpectedVersion_skipsTheCheckButStillBumps() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        service.renamePacket("p", "New", null);

        verify(packetRepository).bumpVersion(eq("p"), isNull(), any(), any());
        verify(packetRepository, never()).currentVersion(anyString());
    }

    @Test
    void matchingExpectedVersion_isPassedAsLong_andBumpPrecedesTheLoad() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        service.addTossupToPacket("p", new TossupInput("Q", "A", null), null, 7);

        InOrder order = inOrder(packetRepository);
        order.verify(packetRepository).bumpVersion(eq("p"), eq(7L), any(), any());
        order.verify(packetRepository).findById("p");
        order.verify(packetRepository).save(packet);
    }

    @Test
    void everyPacketLevelMutation_bumpsTheVersion() {
        Packet packet = packetWithTossups("p", 2);
        Bonus b1 = new Bonus();
        b1.setId("b1");
        Bonus b2 = new Bonus();
        b2.setId("b2");
        packet.getBonuses().add(new ContainsBonus(0, b1));
        packet.getBonuses().add(new ContainsBonus(1, b2));
        Difficulty d = new Difficulty();
        d.setId("d1");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        when(difficultyRepository.findById("d1")).thenReturn(Optional.of(d));
        when(packetRepository.existsById("p")).thenReturn(true);

        service.renamePacket("p", "N", 0);
        service.setPacketDifficulty("p", "d1", 0);
        service.setPacketVisibility("p", PacketVisibility.PUBLISHED, 0);
        service.addTossupToPacket("p", new TossupInput("Q", "A", null), null, 0);
        service.reorderTossup("p", "t0", 1, 0);
        service.removeTossupFromPacket("p", "t1", 0);
        service.addBonusToPacket("p", new BonusInput("P", null, List.of(new BonusPartInput("Q", "A"))), null, 0);
        service.reorderBonus("p", "b1", 0, 0);
        service.removeBonusFromPacket("p", "b2", 0);
        service.deletePacket("p", 0);

        verify(packetRepository, times(10)).bumpVersion(eq("p"), eq(0L), any(), any());
    }

    @Test
    void nodeLevelMutations_bumpTheContainingPacket() {
        Tossup t = tossup("t1", "Q");
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(
                new HasBonusPart(0, partWithId("bp1")), new HasBonusPart(1, partWithId("bp2")))));
        when(packetRepository.findPacketIdByTossupId("t1")).thenReturn(Optional.of("p"));
        when(packetRepository.findPacketIdByBonusId("b1")).thenReturn(Optional.of("p"));
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        service.updateTossup("t1", new TossupInput("Q2", "A2", null), 4);
        service.setTossupSubcategory("t1", null, 4);
        service.updateBonus("b1", new BonusUpdateInput("P", null), 4);
        service.setBonusSubcategory("b1", null, 4);
        service.addBonusPart("b1", new BonusPartInput("Q", "A"), null, 4);
        service.updateBonusPart("b1", "bp1", new BonusPartInput("Q", "A"), 4);
        service.reorderBonusPart("b1", "bp2", 0, 4);
        service.removeBonusPart("b1", "bp1", 4);

        verify(packetRepository, times(8)).bumpVersion(eq("p"), eq(4L), any(), any());
    }

    @Test
    void nodeLevelMutation_staleVersion_leavesTheNodeUntouched() {
        when(packetRepository.findPacketIdByTossupId("t1")).thenReturn(Optional.of("p"));
        when(packetRepository.bumpVersion(eq("p"), eq(1L), any(), any())).thenReturn(null);
        when(packetRepository.currentVersion("p")).thenReturn(Optional.of(2L));

        assertThatThrownBy(() -> service.updateTossup("t1", new TossupInput("Q", "A", null), 1))
                .isInstanceOf(PacketVersionConflictException.class);
        verify(tossupRepository, never()).findById(anyString());
        verify(tossupRepository, never()).save(any());
    }

    @Test
    void nodeOutsideAnyPacket_hasNoVersionToBump() {
        Tossup t = tossup("orphan", "Q");
        when(tossupRepository.findById("orphan")).thenReturn(Optional.of(t));
        service.updateTossup("orphan", new TossupInput("Q", "A", null), 9);
        verify(packetRepository, never()).bumpVersion(anyString(), any(), any(), any());
    }

    @Test
    void setPacketVisibility_bumpsTheVersion() {
        Packet packet = packetWithId("p1");
        packet.setVisibility(PacketVisibility.DRAFT);
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packet));

        service.setPacketVisibility("p1", PacketVisibility.PUBLISHED, 2);

        InOrder order = inOrder(packetRepository);
        order.verify(packetRepository).bumpVersion(eq("p1"), eq(2L), any(), any());
        order.verify(packetRepository).findById("p1");
    }

    @Test
    void setPacketVisibility_staleExpectedVersion_isRejected() {
        when(packetRepository.bumpVersion(eq("p1"), eq(2L), any(), any())).thenReturn(null);
        when(packetRepository.currentVersion("p1")).thenReturn(Optional.of(3L));

        assertThatThrownBy(() -> service.setPacketVisibility("p1", PacketVisibility.PUBLISHED, 2))
                .isInstanceOf(PacketVersionConflictException.class);
        verify(packetRepository, never()).save(any());
    }

    @Test
    void deletePacket_staleExpectedVersion_doesNotDelete() {
        when(packetRepository.existsById("p")).thenReturn(true);
        when(packetRepository.bumpVersion(eq("p"), eq(0L), any(), any())).thenReturn(null);
        when(packetRepository.currentVersion("p")).thenReturn(Optional.of(4L));

        assertThatThrownBy(() -> service.deletePacket("p", 0))
                .isInstanceOf(PacketVersionConflictException.class);
        verify(packetRepository, never()).deletePacketCascade(anyString());
    }
}
