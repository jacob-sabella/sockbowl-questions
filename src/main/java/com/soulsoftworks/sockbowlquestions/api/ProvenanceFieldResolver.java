package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Bonus;
import com.soulsoftworks.sockbowlquestions.models.nodes.BonusPart;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.Tossup;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import com.soulsoftworks.sockbowlquestions.security.PacketReadPolicy;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * Gates {@code createdById}/{@code lastModifiedById} (D13, M4-PV-01): who made or last
 * touched a piece of content carries caller identity, so it's visible only to whoever
 * may read the containing packet in full — the owner, {@code packet:manage-any}, the
 * game service token, or anyone at all when {@code sockbowl.auth.enabled=false} — via
 * the same rule {@link PacketReadPolicy#canReadFull} already applies to answers.
 * Everyone else (anonymous callers, non-owners) gets {@code null}.
 *
 * <p>The M4 schema block's other new fields ({@code createdAt}, {@code lastModifiedAt},
 * {@code source}, {@code aiModel}) carry no caller identity and are deliberately left
 * ungated. {@code createdAt}/{@code lastModifiedAt} are still mapped here, because the
 * schema exposes them as {@code String} (ISO-8601) while the node field is a Java
 * {@link java.time.Instant}, which graphql-java's built-in {@code String} scalar can't
 * coerce on its own.
 */
@Controller
public class ProvenanceFieldResolver {

    private final PacketRepository packetRepository;
    private final PacketReadPolicy readPolicy;

    public ProvenanceFieldResolver(PacketRepository packetRepository, PacketReadPolicy readPolicy) {
        this.packetRepository = packetRepository;
        this.readPolicy = readPolicy;
    }

    @SchemaMapping(typeName = "Packet", field = "createdAt")
    public String packetCreatedAt(Packet packet) {
        return iso(packet.getCreatedAt());
    }

    @SchemaMapping(typeName = "Packet", field = "lastModifiedAt")
    public String packetLastModifiedAt(Packet packet) {
        return iso(packet.getLastModifiedAt());
    }

    @SchemaMapping(typeName = "Tossup", field = "createdAt")
    public String tossupCreatedAt(Tossup tossup) {
        return iso(tossup.getCreatedAt());
    }

    @SchemaMapping(typeName = "Tossup", field = "lastModifiedAt")
    public String tossupLastModifiedAt(Tossup tossup) {
        return iso(tossup.getLastModifiedAt());
    }

    @SchemaMapping(typeName = "Bonus", field = "createdAt")
    public String bonusCreatedAt(Bonus bonus) {
        return iso(bonus.getCreatedAt());
    }

    @SchemaMapping(typeName = "Bonus", field = "lastModifiedAt")
    public String bonusLastModifiedAt(Bonus bonus) {
        return iso(bonus.getLastModifiedAt());
    }

    @SchemaMapping(typeName = "BonusPart", field = "createdAt")
    public String bonusPartCreatedAt(BonusPart bonusPart) {
        return iso(bonusPart.getCreatedAt());
    }

    @SchemaMapping(typeName = "BonusPart", field = "lastModifiedAt")
    public String bonusPartLastModifiedAt(BonusPart bonusPart) {
        return iso(bonusPart.getLastModifiedAt());
    }

    private static String iso(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    @SchemaMapping(typeName = "Packet", field = "createdById")
    public String packetCreatedById(Packet packet) {
        return visible(packet) ? packet.getCreatedBy() : null;
    }

    @SchemaMapping(typeName = "Packet", field = "lastModifiedById")
    public String packetLastModifiedById(Packet packet) {
        return visible(packet) ? packet.getLastModifiedBy() : null;
    }

    @SchemaMapping(typeName = "Tossup", field = "createdById")
    public String tossupCreatedById(Tossup tossup) {
        return visible(owningPacket(tossup.getId())) ? tossup.getCreatedBy() : null;
    }

    @SchemaMapping(typeName = "Tossup", field = "lastModifiedById")
    public String tossupLastModifiedById(Tossup tossup) {
        return visible(owningPacket(tossup.getId())) ? tossup.getLastModifiedBy() : null;
    }

    @SchemaMapping(typeName = "Bonus", field = "createdById")
    public String bonusCreatedById(Bonus bonus) {
        return visible(owningPacketForBonus(bonus.getId())) ? bonus.getCreatedBy() : null;
    }

    @SchemaMapping(typeName = "Bonus", field = "lastModifiedById")
    public String bonusLastModifiedById(Bonus bonus) {
        return visible(owningPacketForBonus(bonus.getId())) ? bonus.getLastModifiedBy() : null;
    }

    @SchemaMapping(typeName = "BonusPart", field = "createdById")
    public String bonusPartCreatedById(BonusPart bonusPart) {
        return visible(owningPacketForBonusPart(bonusPart.getId())) ? bonusPart.getCreatedBy() : null;
    }

    @SchemaMapping(typeName = "BonusPart", field = "lastModifiedById")
    public String bonusPartLastModifiedById(BonusPart bonusPart) {
        return visible(owningPacketForBonusPart(bonusPart.getId())) ? bonusPart.getLastModifiedBy() : null;
    }

    private boolean visible(Packet owningPacket) {
        if (owningPacket == null) {
            return false;
        }
        Authentication auth = PacketReadPolicy.currentAuthentication();
        return readPolicy.canReadFull(auth, owningPacket);
    }

    private Packet owningPacket(String tossupId) {
        return packetRepository.findByTossupId(tossupId).orElse(null);
    }

    private Packet owningPacketForBonus(String bonusId) {
        return packetRepository.findByBonusId(bonusId).orElse(null);
    }

    private Packet owningPacketForBonusPart(String bonusPartId) {
        return packetRepository.findByBonusPartId(bonusPartId).orElse(null);
    }
}
