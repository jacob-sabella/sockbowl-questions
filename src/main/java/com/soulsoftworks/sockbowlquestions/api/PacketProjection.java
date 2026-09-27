package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsBonus;
import com.soulsoftworks.sockbowlquestions.models.relationships.ContainsTossup;
import com.soulsoftworks.sockbowlquestions.models.relationships.HasBonusPart;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.springframework.security.core.Authentication;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * What a caller gets back when it reads a packet (D2).
 *
 * <p>A caller who may read the packet in full gets it as loaded. Everyone else gets
 * an <em>answer-free projection</em>: a deep copy whose {@link Tossup#getAnswer()} and
 * {@link BonusPart#getAnswer()} are {@code null} and whose
 * {@link Packet#getAnswersRedacted()} is {@code true}. Question text is kept.
 *
 * <p>The projection never mutates its input. The packet may be a managed entity
 * (loaded through a repository and saved later in the same request), so every
 * node on the path to an answer is copied; nodes without answers (difficulty,
 * taxonomy) are shared. The copies use the Lombok {@code toBuilder}, so fields
 * added to the models later are carried over automatically.
 */
public final class PacketProjection {

    private PacketProjection() {}

    /**
     * The packet as {@code auth} may see it: the original when the policy allows a full
     * read, otherwise {@link #answerFree(Packet)}. Callers must already have checked
     * {@link PacketReadPolicy#canSee(Authentication, Packet)}.
     */
    public static Packet forCaller(PacketReadPolicy policy, Authentication auth, Packet packet) {
        if (packet == null) {
            return null;
        }
        return policy.canReadFull(auth, packet) ? packet : answerFree(packet);
    }

    /** A deep copy of {@code packet} with every answer removed and {@code answersRedacted=true}. */
    public static Packet answerFree(Packet packet) {
        if (packet == null) {
            return null;
        }
        Packet copy = packet.toBuilder().answersRedacted(true).build();
        // Replace (not append to) the @Singular lists, keeping null as null so a shallow
        // packet (relationships not loaded) stays shallow.
        copy.setTossups(mapList(packet.getTossups(), PacketProjection::answerFree));
        copy.setBonuses(mapList(packet.getBonuses(), PacketProjection::answerFree));
        return copy;
    }

    private static ContainsTossup answerFree(ContainsTossup rel) {
        if (rel == null) {
            return null;
        }
        Tossup tossup = rel.getTossup();
        return rel.toBuilder()
                .tossup(tossup == null ? null : tossup.toBuilder().answer(null).build())
                .build();
    }

    private static ContainsBonus answerFree(ContainsBonus rel) {
        if (rel == null) {
            return null;
        }
        Bonus bonus = rel.getBonus();
        Bonus bonusCopy = null;
        if (bonus != null) {
            bonusCopy = bonus.toBuilder().build();
            bonusCopy.setBonusParts(mapList(bonus.getBonusParts(), PacketProjection::answerFree));
        }
        return rel.toBuilder().bonus(bonusCopy).build();
    }

    private static HasBonusPart answerFree(HasBonusPart rel) {
        if (rel == null) {
            return null;
        }
        BonusPart part = rel.getBonusPart();
        return rel.toBuilder()
                .bonusPart(part == null ? null : part.toBuilder().answer(null).build())
                .build();
    }

    private static <T> List<T> mapList(List<T> source, UnaryOperator<T> mapper) {
        if (source == null) {
            return null;
        }
        List<T> out = new ArrayList<>(source.size());
        for (T item : source) {
            out.add(mapper.apply(item));
        }
        return out;
    }
}
