package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.BonusInput;
import com.soulsoftworks.sockbowlquestions.api.input.BonusPartInput;
import com.soulsoftworks.sockbowlquestions.api.input.CreatePacketInput;
import com.soulsoftworks.sockbowlquestions.api.input.GenerateTossupInput;
import com.soulsoftworks.sockbowlquestions.api.input.TossupInput;
import com.soulsoftworks.sockbowlquestions.config.AiSecurityProperties;
import com.soulsoftworks.sockbowlquestions.dto.AiRequestContext;
import com.soulsoftworks.sockbowlquestions.exception.InvalidApiRequestException;
import com.soulsoftworks.sockbowlquestions.exception.ResourceNotFoundException;
import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.repository.BonusPartRepository;
import com.soulsoftworks.sockbowlquestions.repository.BonusRepository;
import com.soulsoftworks.sockbowlquestions.repository.CategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.DifficultyRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.repository.SubcategoryRepository;
import com.soulsoftworks.sockbowlquestions.repository.TossupRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PacketAuthoringServiceTest {

    @Mock private PacketRepository packetRepository;
    @Mock private TossupRepository tossupRepository;
    @Mock private BonusRepository bonusRepository;
    @Mock private BonusPartRepository bonusPartRepository;
    @Mock private DifficultyRepository difficultyRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private SubcategoryRepository subcategoryRepository;
    @Mock private QuestionGenerationService questionGenerationService;

    private AiSecurityProperties aiSecurityProperties;
    private PacketAuthoringService service;

    @BeforeEach
    void setUp() {
        aiSecurityProperties = new AiSecurityProperties();
        service = new PacketAuthoringService(packetRepository, tossupRepository, bonusRepository,
                bonusPartRepository, difficultyRepository, categoryRepository, subcategoryRepository,
                questionGenerationService, aiSecurityProperties);
        // Most paths save then return the saved entity; echo the argument back.
        lenient().when(packetRepository.save(any(Packet.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bonusRepository.save(any(Bonus.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(tossupRepository.save(any(Tossup.class))).thenAnswer(inv -> inv.getArgument(0));
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
        Packet result = service.createPacket(new CreatePacketInput("My Packet", null));
        assertThat(result.getName()).isEqualTo("My Packet");
        verify(packetRepository).save(any(Packet.class));
    }

    @Test
    void createPacket_blankName_throws() {
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput("  ", null)))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(packetRepository, never()).save(any());
    }

    @Test
    void createPacket_withDifficulty_resolvesReference() {
        Difficulty d = new Difficulty();
        d.setId("d1");
        when(difficultyRepository.findById("d1")).thenReturn(Optional.of(d));
        Packet result = service.createPacket(new CreatePacketInput("P", "d1"));
        assertThat(result.getDifficulty()).isSameAs(d);
    }

    @Test
    void createPacket_withMissingDifficulty_throwsNotFound() {
        when(difficultyRepository.findById("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createPacket(new CreatePacketInput("P", "nope")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void renamePacket_missing_throwsNotFound() {
        when(packetRepository.findById("x")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.renamePacket("x", "New"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deletePacket_missing_throwsNotFound() {
        when(packetRepository.existsById("x")).thenReturn(false);
        assertThatThrownBy(() -> service.deletePacket("x"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(packetRepository, never()).deleteById(anyString());
    }

    @Test
    void deletePacket_present_deletes() {
        when(packetRepository.existsById("p")).thenReturn(true);
        assertThat(service.deletePacket("p")).isTrue();
        verify(packetRepository).deleteById("p");
    }

    /* ------------------------------- Tossups ------------------------------- */

    @Test
    void addTossup_appendsAndOrders() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        service.addTossupToPacket("p", new TossupInput("Q1", "A1", null), null);
        Packet result = service.addTossupToPacket("p", new TossupInput("Q2", "A2", null), null);

        assertThat(tossupOrders(result)).containsExactly(0, 1);
        assertThat(result.getTossups().get(1).getTossup().getQuestion()).isEqualTo("Q2");
    }

    @Test
    void addTossup_atIndexZero_shiftsExisting() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.addTossupToPacket("p", new TossupInput("Q0", "A0", null), 0);

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
        assertThatThrownBy(() -> service.addTossupToPacket("p", new TossupInput("", "A", null), null))
                .isInstanceOf(InvalidApiRequestException.class);
    }

    @Test
    void updateTossup_setsFields() {
        Tossup t = tossup("t1", "old");
        when(tossupRepository.findById("t1")).thenReturn(Optional.of(t));
        Tossup result = service.updateTossup("t1", new TossupInput("new", "ans", null));
        assertThat(result.getQuestion()).isEqualTo("new");
        assertThat(result.getAnswer()).isEqualTo("ans");
    }

    @Test
    void removeTossup_renumbersRemaining() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        packet.getTossups().add(ContainsTossup.builder().order(2).tossup(tossup("t3", "Q3")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.removeTossupFromPacket("p", "t2");

        assertThat(tossupIdsInOrder(result)).containsExactly("t1", "t3");
        assertThat(tossupOrders(result)).containsExactly(0, 1);
        verify(tossupRepository).deleteById("t2");
    }

    @Test
    void removeTossup_notInPacket_throwsNotFound() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        assertThatThrownBy(() -> service.removeTossupFromPacket("p", "ghost"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void reorderTossup_movesAndRenumbers() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        packet.getTossups().add(ContainsTossup.builder().order(2).tossup(tossup("t3", "Q3")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.reorderTossup("p", "t3", 0);

        assertThat(tossupIdsInOrder(result)).containsExactly("t3", "t1", "t2");
        assertThat(tossupOrders(result)).containsExactly(0, 1, 2);
    }

    @Test
    void reorderTossup_oversizedNewOrder_clampsToEnd() {
        Packet packet = packetWithId("p");
        packet.getTossups().add(ContainsTossup.builder().order(0).tossup(tossup("t1", "Q1")).build());
        packet.getTossups().add(ContainsTossup.builder().order(1).tossup(tossup("t2", "Q2")).build());
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.reorderTossup("p", "t1", 99);

        assertThat(tossupIdsInOrder(result)).containsExactly("t2", "t1");
    }

    /* -------------------------------- Bonuses ------------------------------ */

    @Test
    void addBonus_withParts_ordersParts() {
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        BonusInput input = new BonusInput("Preamble", null,
                List.of(new BonusPartInput("BQ1", "BA1"), new BonusPartInput("BQ2", "BA2")));
        Packet result = service.addBonusToPacket("p", input, null);

        assertThat(result.getBonuses()).hasSize(1);
        ContainsBonus cb = result.getBonuses().get(0);
        assertThat(cb.getOrder()).isZero();
        assertThat(cb.getBonus().getBonusParts()).hasSize(2);
        assertThat(cb.getBonus().getBonusParts().get(0).getOrder()).isZero();
        assertThat(cb.getBonus().getBonusParts().get(1).getOrder()).isEqualTo(1);
    }

    @Test
    void addBonus_secondBonus_getsNextOrder() {
        Packet packet = packetWithId("p");
        Bonus existing = new Bonus();
        existing.setId("b1");
        packet.getBonuses().add(new ContainsBonus(0, existing));
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));

        Packet result = service.addBonusToPacket("p", new BonusInput("P2", null, null), null);

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

        Packet result = service.removeBonusFromPacket("p", "b1");

        assertThat(result.getBonuses()).hasSize(1);
        assertThat(result.getBonuses().get(0).getOrder()).isZero();
        assertThat(result.getBonuses().get(0).getBonus().getId()).isEqualTo("b2");
        verify(bonusRepository).deleteById("b1");
    }

    @Test
    void updateBonus_missing_throwsNotFound() {
        when(bonusRepository.findById("b")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.updateBonus("b",
                new com.soulsoftworks.sockbowlquestions.api.input.BonusUpdateInput("x", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ------------------------------ Bonus parts ---------------------------- */

    @Test
    void addBonusPart_appendsWithOrder() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(new HasBonusPart(0, partWithId("bp1")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.addBonusPart("b1", new BonusPartInput("Q", "A"), null);

        assertThat(result.getBonusParts()).hasSize(2);
        assertThat(result.getBonusParts().get(1).getOrder()).isEqualTo(1);
    }

    @Test
    void removeBonusPart_renumbersAndDeletes() {
        Bonus bonus = new Bonus();
        bonus.setId("b1");
        bonus.setBonusParts(new ArrayList<>(List.of(
                new HasBonusPart(0, partWithId("bp1")),
                new HasBonusPart(1, partWithId("bp2")))));
        when(bonusRepository.findById("b1")).thenReturn(Optional.of(bonus));

        Bonus result = service.removeBonusPart("b1", "bp1");

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

        Bonus result = service.reorderBonusPart("b1", "bp3", 0);

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
        assertThatThrownBy(() -> service.generateAndAddTossup("p", input, null))
                .isInstanceOf(InvalidApiRequestException.class);
        verify(questionGenerationService, never()).generateTossup(any(), any(), anyList(), any());
    }

    @Test
    void generateAndAddTossup_addsGeneratedTossup() {
        aiSecurityProperties.setRequireUserApiKey(false);
        Packet packet = packetWithId("p");
        when(packetRepository.findById("p")).thenReturn(Optional.of(packet));
        Tossup generated = tossup("gen", "Generated?");
        when(questionGenerationService.generateTossup(anyString(), any(), anyList(), any(AiRequestContext.class)))
                .thenReturn(generated);

        GenerateTossupInput input = new GenerateTossupInput("Science", "context", null, null, null);
        Packet result = service.generateAndAddTossup("p", input, null);

        assertThat(result.getTossups()).hasSize(1);
        assertThat(result.getTossups().get(0).getTossup().getQuestion()).isEqualTo("Generated?");
        assertThat(result.getTossups().get(0).getOrder()).isZero();
    }

    /* ------------------------------- Taxonomy ------------------------------ */

    @Test
    void createSubcategory_missingCategory_throwsNotFound() {
        when(categoryRepository.findById("c")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.createSubcategory("Bio", "c"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createDifficulty_persists() {
        when(difficultyRepository.save(any(Difficulty.class))).thenAnswer(inv -> inv.getArgument(0));
        Difficulty result = service.createDifficulty("Hard");
        assertThat(result.getName()).isEqualTo("Hard");
    }
}
