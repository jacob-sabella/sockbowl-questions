package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
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
 */
@Service("packetAuthorizationService")
public class PacketAuthorizationService {

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

    private boolean canManage(Authentication auth, Packet packet) {
        if (packet == null) {
            // Let the mutation's own not-found path handle a missing packet/node.
            return false;
        }
        if (packet.getOwnerId() == null) {
            // Grandfather rule: any authenticated author-tier user may manage legacy packets.
            return true;
        }
        return packet.getOwnerId().equals(auth.getName());
    }

    private Authentication currentAuth() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth : null;
    }

    private boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals(authority));
    }
}
