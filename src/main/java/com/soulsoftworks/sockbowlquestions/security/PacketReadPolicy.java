package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Who may see a packet, and who may see its answers (D2, AUTH-03). Bean name
 * {@code packetReadPolicy}, so it can be used from {@code @PreAuthorize} SpEL.
 *
 * <ul>
 *   <li>{@link #canReadFull}: the whole packet including answers. With auth disabled,
 *       always true (self-hosted behavior is unchanged). Otherwise the caller holds
 *       {@code packet:read-answers} (the game service token) or
 *       {@code packet:manage-any}, or is the packet's recorded owner. A game-only
 *       packet ({@link PacketVisibility#isGameOnly()}, i.e. EPHEMERAL, D15) is readable
 *       in full only with {@code packet:read-answers}: not by manage-any, and it has no
 *       owner.</li>
 *   <li>{@link #canSee}: the packet exists for this caller at all. True when its
 *       effective visibility is publicly readable (PUBLISHED, or a legacy node with no
 *       visibility), or when the caller can read it in full.</li>
 * </ul>
 * A caller that can see but not fully read a packet gets the answer-free projection
 * ({@code api.PacketProjection}). A caller that cannot see it gets nothing: reads
 * return {@code null} or leave it out of lists, so there is no existence oracle.
 *
 * <p>Lists and searches additionally drop game-only packets for every caller, including
 * those who {@link #canReadEveryPacket read every packet} ({@link #isListed},
 * {@link #unlistedVisibilities}).
 *
 * <p>The rules are written against {@link PacketVisibility#isPubliclyReadable()}, never
 * against specific values, so a new visibility can be added to the enum without
 * touching them.
 */
@Component("packetReadPolicy")
public class PacketReadPolicy {

    /** Full packet content for the game backend's service token (D2, plan P1). */
    public static final String READ_ANSWERS = "packet:read-answers";
    /** Moderators/admins: manage and read any packet. */
    public static final String MANAGE_ANY = "packet:manage-any";

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final PacketRepository packetRepository;
    private final boolean authEnabled;

    public PacketReadPolicy(PacketRepository packetRepository,
                            @Value("${sockbowl.auth.enabled:false}") boolean authEnabled) {
        this.packetRepository = packetRepository;
        this.authEnabled = authEnabled;
    }

    /** True when {@code auth} may see {@code packet} (possibly as the answer-free projection). */
    public boolean canSee(Authentication auth, Packet packet) {
        if (packet == null) {
            return false;
        }
        return PacketVisibility.effective(packet.getVisibility()).isPubliclyReadable()
                || canReadFull(auth, packet);
    }

    /** True when {@code auth} may read {@code packet} including its answers. */
    public boolean canReadFull(Authentication auth, Packet packet) {
        if (packet == null) {
            return false;
        }
        if (!authEnabled) {
            return true;
        }
        if (PacketVisibility.effective(packet.getVisibility()).isGameOnly()) {
            // D15: only the game service reads a game-only packet, to play it.
            return isRealUser(auth) && hasAuthority(auth, READ_ANSWERS);
        }
        return canReadEveryPacket(auth) || isOwner(auth, packet);
    }

    /**
     * True when {@code packet} may appear in list and search results. Game-only packets
     * never do, whoever is asking; callers must also check {@link #canSee}.
     */
    public boolean isListed(Packet packet) {
        return packet != null && PacketVisibility.effective(packet.getVisibility()).isListed();
    }

    /** Id convenience for {@link #canSee(Authentication, Packet)}; an unknown id gives false. */
    public boolean canSee(Authentication auth, String packetId) {
        return canSee(auth, load(packetId));
    }

    /** Id convenience for {@link #canReadFull(Authentication, Packet)}; an unknown id gives false. */
    public boolean canReadFull(Authentication auth, String packetId) {
        return canReadFull(auth, load(packetId));
    }

    /** {@link #canSee(Authentication, String)} for the current security context (SpEL-friendly). */
    public boolean canSee(String packetId) {
        return canSee(currentAuthentication(), packetId);
    }

    /** {@link #canReadFull(Authentication, String)} for the current security context (SpEL-friendly). */
    public boolean canReadFull(String packetId) {
        return canReadFull(currentAuthentication(), packetId);
    }

    /**
     * True when the caller may read every <em>listed</em> packet in full regardless of
     * owner or visibility: auth disabled, {@code packet:read-answers} or
     * {@code packet:manage-any}. Lets list queries skip the visibility filter; they
     * still leave out the {@link #unlistedVisibilities() unlisted} (game-only) packets.
     */
    public boolean canReadEveryPacket(Authentication auth) {
        if (!authEnabled) {
            return true;
        }
        return isRealUser(auth) && (hasAuthority(auth, READ_ANSWERS) || hasAuthority(auth, MANAGE_ANY));
    }

    /** The caller's subject when it is a real (non-anonymous) user, else {@code null}. */
    public String callerId(Authentication auth) {
        return isRealUser(auth) ? auth.getName() : null;
    }

    /** Names of the visibilities anyone may read, for repository-level filtering. */
    public List<String> publiclyReadableVisibilities() {
        return Arrays.stream(PacketVisibility.values())
                .filter(PacketVisibility::isPubliclyReadable)
                .map(Enum::name)
                .toList();
    }

    /** Names of the visibilities that never appear in lists or searches (game-only, D15). */
    public List<String> unlistedVisibilities() {
        return Arrays.stream(PacketVisibility.values())
                .filter(v -> !v.isListed())
                .map(Enum::name)
                .toList();
    }

    /** The name a node without a stored visibility counts as, for repository-level filtering. */
    public String legacyVisibility() {
        return PacketVisibility.effective(null).name();
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public static Authentication currentAuthentication() {
        return SecurityContextHolder.getContext().getAuthentication();
    }

    private boolean isOwner(Authentication auth, Packet packet) {
        String caller = callerId(auth);
        return caller != null && packet.getOwnerId() != null && packet.getOwnerId().equals(caller);
    }

    private Packet load(String packetId) {
        if (packetId == null || packetId.isBlank()) {
            return null;
        }
        return packetRepository.findById(packetId).orElse(null);
    }

    private static boolean isRealUser(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !TRUST_RESOLVER.isAnonymous(auth);
    }

    private static boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
