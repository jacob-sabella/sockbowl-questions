package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Difficulty;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.models.nodes.Subcategory;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class PacketProjectionTest {

    @Test
    void answersAreNulledOnACopyAndTheOriginalIsUntouched() {
        Packet original = fullPacket();

        Packet projected = PacketProjection.answerFree(original);

        // The projection: answers gone, questions and structure kept, flag set.
        assertThat(projected).isNotSameAs(original);
        assertThat(projected.getAnswersRedacted()).isTrue();
        assertThat(projected.getId()).isEqualTo("p1");
        assertThat(projected.getName()).isEqualTo("Packet");
        assertThat(projected.getOwnerId()).isEqualTo("owner");
        assertThat(projected.getVisibility()).isEqualTo(PacketVisibility.PUBLISHED);
        assertThat(projected.getDifficulty()).isSameAs(original.getDifficulty());

        ContainsTossup ct = projected.getTossups().get(0);
        assertThat(ct).isNotSameAs(original.getTossups().get(0));
        assertThat(ct.getOrder()).isEqualTo(0);
        assertThat(ct.getTossup().getId()).isEqualTo("t1");
        assertThat(ct.getTossup().getQuestion()).isEqualTo("Tossup question?");
        assertThat(ct.getTossup().getAnswer()).isNull();
        assertThat(ct.getTossup().getSubcategory()).isSameAs(original.getTossups().get(0).getTossup().getSubcategory());

        ContainsBonus cb = projected.getBonuses().get(0);
        assertThat(cb.getBonus().getPreamble()).isEqualTo("For 10 points each:");
        assertThat(cb.getBonus().getBonusParts()).hasSize(2);
        assertThat(cb.getBonus().getBonusParts())
                .allSatisfy(hp -> {
                    assertThat(hp.getBonusPart().getAnswer()).isNull();
                    assertThat(hp.getBonusPart().getQuestion()).startsWith("Part");
                });

        // The original (possibly a managed entity) is unchanged.
        assertThat(original.getAnswersRedacted()).isNotEqualTo(Boolean.TRUE);
        assertThat(original.getTossups().get(0).getTossup().getAnswer()).isEqualTo("tossup answer");
        assertThat(original.getBonuses().get(0).getBonus().getBonusParts())
                .extracting(hp -> hp.getBonusPart().getAnswer())
                .containsExactly("answer A", "answer B");
    }

    @Test
    void shallowPacketStaysShallow() {
        Packet shallow = new Packet();
        shallow.setId("p2");
        shallow.setName("Shallow");

        Packet projected = PacketProjection.answerFree(shallow);

        assertThat(projected.getTossups()).isNull();
        assertThat(projected.getBonuses()).isNull();
        assertThat(projected.getAnswersRedacted()).isTrue();
        assertThat(PacketProjection.answerFree(null)).isNull();
    }

    @Test
    void nullNodesInsideThePacketAreTolerated() {
        Packet packet = new Packet();
        packet.setTossups(new java.util.ArrayList<>(List.of(new ContainsTossup(null, 0, null))));
        Bonus partless = new Bonus();
        packet.setBonuses(new java.util.ArrayList<>(List.of(new ContainsBonus(0, partless))));

        Packet projected = PacketProjection.answerFree(packet);

        assertThat(projected.getTossups().get(0).getTossup()).isNull();
        assertThat(projected.getBonuses().get(0).getBonus().getBonusParts()).isNull();
    }

    @Test
    void forCallerReturnsTheOriginalOnlyForFullReaders() {
        Packet packet = fullPacket();
        var anonymous = new AnonymousAuthenticationToken("k", "anonymousUser",
                AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS"));

        PacketReadPolicy authOn = new PacketReadPolicy(mock(PacketRepository.class), true);
        PacketReadPolicy authOff = new PacketReadPolicy(mock(PacketRepository.class), false);

        Packet forAnonymous = PacketProjection.forCaller(authOn, anonymous, packet);
        assertThat(forAnonymous).isNotSameAs(packet);
        assertThat(forAnonymous.getAnswersRedacted()).isTrue();

        assertThat(PacketProjection.forCaller(authOff, anonymous, packet)).isSameAs(packet);
    }

    private static Packet fullPacket() {
        Subcategory sub = Subcategory.builder().id("s1").name("Sub").build();
        Tossup tossup = Tossup.builder().id("t1").question("Tossup question?").answer("tossup answer")
                .subcategory(sub).build();
        BonusPart a = BonusPart.builder().id("bp1").question("Part A?").answer("answer A").build();
        BonusPart b = BonusPart.builder().id("bp2").question("Part B?").answer("answer B").build();
        Bonus bonus = Bonus.builder().id("b1").preamble("For 10 points each:")
                .bonusParts(new java.util.ArrayList<>(List.of(new HasBonusPart(0, a), new HasBonusPart(1, b))))
                .build();
        Packet packet = Packet.builder().id("p1").name("Packet").ownerId("owner")
                .visibility(PacketVisibility.PUBLISHED)
                .difficulty(Difficulty.builder().id("d1").name("Easy").build())
                .build();
        packet.setTossups(new java.util.ArrayList<>(List.of(ContainsTossup.builder().order(0).tossup(tossup).build())));
        packet.setBonuses(new java.util.ArrayList<>(List.of(new ContainsBonus(0, bonus))));
        return packet;
    }
}
