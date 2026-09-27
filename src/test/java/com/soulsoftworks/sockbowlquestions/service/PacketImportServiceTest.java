package com.soulsoftworks.sockbowlquestions.service;

import com.soulsoftworks.sockbowlquestions.api.input.ImportPacketInput;
import com.soulsoftworks.sockbowlquestions.config.PacketLimitsProperties;
import com.soulsoftworks.sockbowlquestions.dto.ImportPacketResultDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.packetio.ParseIssue;
import com.soulsoftworks.sockbowlquestions.repository.PacketImportRepository;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PacketImportServiceTest {

    @Mock private PacketImportRepository packetImportRepository;
    @Mock private PacketRepository packetRepository;
    @Mock private PacketReadPolicy packetReadPolicy;

    private PacketImportService service;
    private final AuthenticatedUser author = new AuthenticatedUser("author-sub", "author-name", Set.of(), false);

    private static final String ONE_TOSSUP = """
            TOSSUPS

            1. A perfectly fine question with an answer.
            ANSWER: fine
            """;

    @BeforeEach
    void setUp() {
        service = new PacketImportService(packetImportRepository, packetRepository, packetReadPolicy,
                new PacketLimitsProperties());
    }

    @Test
    void dryRunWritesNothing() {
        ImportPacketInput input = new ImportPacketInput(ONE_TOSSUP, null, null, true, false);

        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.committed()).isFalse();
        assertThat(result.packet()).isNull();
        assertThat(result.parsed().tossups()).hasSize(1);
        verify(packetImportRepository, never()).createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void defaultDryRunAlsoWritesNothing() {
        // dryRun defaults to true (ImportPacketInput.isDryRun()) when the argument is omitted.
        ImportPacketInput input = new ImportPacketInput(ONE_TOSSUP, null, null, null, null);

        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.committed()).isFalse();
        verify(packetImportRepository, never()).createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void errorBlocksCommitUnlessSkipInvalid() {
        String textWithOneErrorAndOneValidTossup = """
                TOSSUPS

                1. This question never gets an answer at all, which is an error.

                2. This one is fine.
                ANSWER: fine
                """;
        ImportPacketInput blocked = new ImportPacketInput(textWithOneErrorAndOneValidTossup, "Packet", null, false, false);

        ImportPacketResultDto blockedResult = service.importPacket(blocked, author);

        assertThat(blockedResult.committed()).isFalse();
        assertThat(blockedResult.packet()).isNull();
        assertThat(blockedResult.issues()).anyMatch(ParseIssue::isError);
        verify(packetImportRepository, never()).createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString());

        when(packetImportRepository.createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn("new-packet-id");
        when(packetRepository.findById("new-packet-id")).thenReturn(Optional.of(Packet.builder().id("new-packet-id").build()));

        ImportPacketInput allowed = new ImportPacketInput(textWithOneErrorAndOneValidTossup, "Packet", null, false, true);
        ImportPacketResultDto allowedResult = service.importPacket(allowed, author);

        assertThat(allowedResult.committed()).isTrue();
        assertThat(allowedResult.packet()).isNotNull();
        verify(packetImportRepository).createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void zeroTossupsIsRefusedEvenWithSkipInvalid() {
        String noTossups = """
                BONUSES

                1. A bonus with no tossups anywhere in the packet.
                [10] a
                ANSWER: a
                [10] b
                ANSWER: b
                [10] c
                ANSWER: c
                """;
        ImportPacketInput input = new ImportPacketInput(noTossups, "Packet", null, false, true);

        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.committed()).isFalse();
        assertThat(result.packet()).isNull();
        verify(packetImportRepository, never()).createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString());
    }

    @Test
    void unknownCategoryTagProducesAWarningAndLeavesTheSubcategoryNull() {
        String withUnknownTag = """
                TOSSUPS

                1. A question tagged with taxonomy that doesn't exist.
                ANSWER: fine <Nonexistent Category - Nonexistent Subcat>
                """;
        when(packetImportRepository.findTaxonomyByKeys(eq("Nonexistent Category"), eq("Nonexistent Subcat")))
                .thenReturn(Optional.empty());
        ImportPacketInput input = new ImportPacketInput(withUnknownTag, null, null, true, false);

        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.parsed().tossups().get(0).subcategory()).isNull();
        assertThat(result.issues()).anyMatch(i -> i.code().equals(ParseIssue.UNKNOWN_CATEGORY_TAG));
    }

    @Test
    void resolvedCategoryTagAttachesTheSubcategoryWithNoWarning() {
        String withKnownTag = """
                TOSSUPS

                1. A question tagged with taxonomy that does exist.
                ANSWER: fine <Science - Chemistry>
                """;
        Subcategory chemistry = Subcategory.builder().id("sub-chem").name("Chemistry").build();
        when(packetImportRepository.findTaxonomyByKeys(eq("Science"), eq("Chemistry")))
                .thenReturn(Optional.of(chemistry));
        ImportPacketInput input = new ImportPacketInput(withKnownTag, null, null, true, false);

        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.parsed().tossups().get(0).subcategory()).isEqualTo(chemistry);
        assertThat(result.issues()).noneMatch(i -> i.code().equals(ParseIssue.UNKNOWN_CATEGORY_TAG));
    }

    @Test
    @SuppressWarnings("unchecked")
    void ownerIsSetFromTheAuthenticatedUserOnCommit() {
        when(packetImportRepository.createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn("new-packet-id");
        when(packetRepository.findById("new-packet-id"))
                .thenReturn(Optional.of(Packet.builder().id("new-packet-id").build()));

        ImportPacketInput input = new ImportPacketInput(ONE_TOSSUP, "My Packet", null, false, false);
        ImportPacketResultDto result = service.importPacket(input, author);

        assertThat(result.committed()).isTrue();
        ArgumentCaptor<String> ownerIdCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> ownerNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(packetImportRepository).createImportedPacket(
                eq("My Packet"), any(), any(), any(), ownerIdCaptor.capture(), ownerNameCaptor.capture(),
                eq("DRAFT"), eq(PacketImportService.CREATED_VIA_IMPORT));
        assertThat(ownerIdCaptor.getValue()).isEqualTo("author-sub");
        assertThat(ownerNameCaptor.getValue()).isEqualTo("author-name");
    }

    @Test
    @SuppressWarnings("unchecked")
    void commitBuildsOrderedRowsFromTheResolvedTossupsAndBonuses() {
        when(packetImportRepository.createImportedPacket(
                anyString(), any(), any(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn("new-packet-id");
        when(packetRepository.findById("new-packet-id"))
                .thenReturn(Optional.of(Packet.builder().id("new-packet-id").build()));

        String twoTossupsOneBonus = """
                TOSSUPS

                1. First question.
                ANSWER: first

                2. Second question.
                ANSWER: second

                BONUSES

                1. A bonus preamble.
                [10] part a
                ANSWER: a
                [10] part b
                ANSWER: b
                [10] part c
                ANSWER: c
                """;
        ImportPacketInput input = new ImportPacketInput(twoTossupsOneBonus, "Packet", null, false, false);
        service.importPacket(input, author);

        ArgumentCaptor<List<Map<String, Object>>> tossupRowsCaptor = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<List<Map<String, Object>>> bonusRowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(packetImportRepository).createImportedPacket(
                anyString(), any(), tossupRowsCaptor.capture(), bonusRowsCaptor.capture(),
                anyString(), any(), anyString(), anyString());

        List<Map<String, Object>> tossupRows = tossupRowsCaptor.getValue();
        assertThat(tossupRows).hasSize(2);
        assertThat(tossupRows.get(0).get("order")).isEqualTo(0);
        assertThat(tossupRows.get(1).get("order")).isEqualTo(1);
        assertThat(tossupRows.get(0).get("question")).isEqualTo("First question.");
        assertThat(tossupRows.get(0).get("subcategoryId")).isNull();

        List<Map<String, Object>> bonusRows = bonusRowsCaptor.getValue();
        assertThat(bonusRows).hasSize(1);
        List<?> parts = (List<?>) bonusRows.get(0).get("parts");
        assertThat(parts).hasSize(3);
    }
}
