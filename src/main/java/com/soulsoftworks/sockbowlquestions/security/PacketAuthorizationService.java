package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.models.nodes.PacketVisibility;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Per-packet ownership check, referenced from {@code @PreAuthorize} SpEL as
 * {@code @packetAuthorizationService.canManage(...)}. AND'd with the coarse
 * {@code hasAuthority(...)} gate on every mutation that acts on an existing
 * packet, so a caller must hold the relevant authority AND either own the
 * packet or hold {@code packet:manage-any}.
 *
 * <p>This bean is only ever consulted when {@code @EnableMethodSecurity} is
 * active, i.e. only under {@link com.soulsoftworks.sockbowlquestions.config.SecurityConfig}.
 * Under {@code NoSecurityConfig} (auth disabled) method security isn't engaged
 * at all, so ownership is never checked and behavior is unchanged.
 *
 * <p><strong>Ownerless packets</strong> (D3, closes PB-17): a packet with no
 * {@code ownerId} (legacy data, or one imported while auth was off) can only be
 * managed through the {@code packet:manage-any} short-circuit. There is no
 * grandfather rule letting any author edit or delete them.
 *
 * <p><strong>Game-only packets</strong> (EPHEMERAL, D15): nobody may manage them, not
 * even {@code packet:manage-any}. The only exception is deletion, which
 * {@link #canDelete(String)} allows to {@code packet:manage-any} (they are also
 * removed automatically after their TTL). The {@code manage-any} short-circuit
 * therefore loads the packet first; a packet that doesn't exist still passes it, so
 * the mutation reports "not found" to a moderator as before.
 */
@Service("packetAuthorizationService")
public class PacketAuthorizationService {

    private static final String MANAGE_ANY = "packet:manage-any";

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final PacketRepository packetRepository;

    public PacketAuthorizationService(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    /** Ownership check against a packet id directly. */
    public boolean canManage(String packetId) {
        return decide(packetRepository.findById(packetId));
    }

    /**
     * Delete check against a packet id: {@link #canManage(String)}, plus
     * {@code packet:manage-any} may delete a game-only (EPHEMERAL) packet, which nobody
     * may otherwise manage (D15).
     */
    public boolean canDelete(String packetId) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        if (hasAuthority(auth, MANAGE_ANY)) {
            return true;
        }
        return canManage(auth, packetRepository.findById(packetId).orElse(null));
    }

    /** Ownership check resolved via the packet that contains the given tossup. */
    public boolean canManageTossup(String tossupId) {
        return decide(packetRepository.findByTossupId(tossupId));
    }

    /** Ownership check resolved via the packet that contains the given bonus (also used for bonus parts, keyed by bonusId). */
    public boolean canManageBonus(String bonusId) {
        return decide(packetRepository.findByBonusId(bonusId));
    }

    /**
     * The id-based decision for the current caller. A missing packet passes for
     * {@code packet:manage-any} (so the mutation itself reports "not found") and fails
     * for everyone else; an existing packet goes through {@link #canManage(Authentication, Packet)}.
     */
    private boolean decide(Optional<Packet> packet) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        if (packet.isEmpty()) {
            return hasAuthority(auth, MANAGE_ANY);
        }
        return canManage(auth, packet.get());
    }

    /**
     * Ownership decision for an already-loaded packet: {@code packet:manage-any}, or the
     * caller is the recorded owner. A missing packet, an ownerless packet (D3), a
     * game-only packet (D15) and an anonymous caller all give {@code false}.
     */
    public boolean canManage(Authentication auth, Packet packet) {
        if (!isRealUser(auth) || packet == null) {
            // A missing packet/node: the mutation is denied rather than leaking existence.
            return false;
        }
        if (PacketVisibility.effective(packet.getVisibility()).isGameOnly()) {
            // D15: never editable, whoever asks. Deletion goes through canDelete.
            return false;
        }
        if (hasAuthority(auth, MANAGE_ANY)) {
            return true;
        }
        // D3: ownerless packets are manageable only via packet:manage-any (handled above).
        return packet.getOwnerId() != null && packet.getOwnerId().equals(auth.getName());
    }

    private Authentication currentAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return isRealUser(auth) ? auth : null;
    }

    private static boolean isRealUser(Authentication auth) {
        return auth != null && auth.isAuthenticated() && !TRUST_RESOLVER.isAnonymous(auth);
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }
}
