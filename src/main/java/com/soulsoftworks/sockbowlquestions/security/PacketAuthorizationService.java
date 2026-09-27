package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

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
 */
@Service("packetAuthorizationService")
public class PacketAuthorizationService {

    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final PacketRepository packetRepository;

    public PacketAuthorizationService(PacketRepository packetRepository) {
        this.packetRepository = packetRepository;
    }

    /** Ownership check against a packet id directly. */
    public boolean canManage(String packetId) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        if (hasAuthority(auth, "packet:manage-any")) {
            return true;
        }
        Packet packet = packetRepository.findById(packetId).orElse(null);
        return canManage(auth, packet);
    }

    /** Ownership check resolved via the packet that contains the given tossup. */
    public boolean canManageTossup(String tossupId) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        if (hasAuthority(auth, "packet:manage-any")) {
            return true;
        }
        Packet packet = packetRepository.findByTossupId(tossupId).orElse(null);
        return canManage(auth, packet);
    }

    /** Ownership check resolved via the packet that contains the given bonus (also used for bonus parts, keyed by bonusId). */
    public boolean canManageBonus(String bonusId) {
        Authentication auth = currentAuth();
        if (auth == null) {
            return false;
        }
        if (hasAuthority(auth, "packet:manage-any")) {
            return true;
        }
        Packet packet = packetRepository.findByBonusId(bonusId).orElse(null);
        return canManage(auth, packet);
    }

    /**
     * Ownership decision for an already-loaded packet: {@code packet:manage-any}, or the
     * caller is the recorded owner. A missing packet, an ownerless packet (D3) and an
     * anonymous caller all give {@code false}.
     */
    public boolean canManage(Authentication auth, Packet packet) {
        if (!isRealUser(auth) || packet == null) {
            // A missing packet/node: the mutation is denied rather than leaking existence.
            return false;
        }
        if (hasAuthority(auth, "packet:manage-any")) {
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
