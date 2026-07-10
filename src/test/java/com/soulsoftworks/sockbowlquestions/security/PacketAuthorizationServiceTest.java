package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage of the ownership decision in {@link PacketAuthorizationService},
 * exercised directly against the {@link org.springframework.security.core.context.SecurityContextHolder}
 * rather than through a MockMvc slice, since the SpEL wiring itself is proven by
 * {@code PacketAuthorizationTest}.
 */
@ExtendWith(MockitoExtension.class)
class PacketAuthorizationServiceTest {

    @Mock private PacketRepository packetRepository;

    private PacketAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new PacketAuthorizationService(packetRepository);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String sub, String... authorities) {
        Authentication auth = new TestingAuthenticationToken(sub, "n/a",
                java.util.Arrays.stream(authorities).map(SimpleGrantedAuthority::new).toList());
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Packet packetOwnedBy(String ownerId) {
        Packet p = new Packet();
        p.setId("p1");
        p.setOwnerId(ownerId);
        return p;
    }

    @Test
    void ownerEditingOwnPacket_isAllowed() {
        authenticateAs("sub-A", "packet:update");
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        assertThat(service.canManage("p1")).isTrue();
    }

    @Test
    void nonOwnerWithoutManageAny_isDenied() {
        authenticateAs("sub-B", "packet:update");
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        assertThat(service.canManage("p1")).isFalse();
    }

    @Test
    void manageAnyAuthority_allowsAnyPacket() {
        authenticateAs("sub-C", "packet:manage-any");

        assertThat(service.canManage("p1")).isTrue();
    }

    @Test
    void grandfatheredOwnerlessPacket_isManageableByAnyAuthenticatedUser() {
        authenticateAs("sub-B", "packet:update");
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy(null)));

        assertThat(service.canManage("p1")).isTrue();
    }

    @Test
    void missingPacket_isDenied() {
        authenticateAs("sub-A", "packet:update");
        when(packetRepository.findById("missing")).thenReturn(Optional.empty());

        assertThat(service.canManage("missing")).isFalse();
    }

    @Test
    void noAuthentication_isDenied() {
        assertThat(service.canManage("p1")).isFalse();
    }

    @Test
    void canManageTossup_resolvesOwningPacket() {
        authenticateAs("sub-A", "packet:update");
        when(packetRepository.findByTossupId("t1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        assertThat(service.canManageTossup("t1")).isTrue();
    }

    @Test
    void canManageBonus_deniesNonOwner() {
        authenticateAs("sub-B", "packet:update");
        when(packetRepository.findByBonusId("b1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        assertThat(service.canManageBonus("b1")).isFalse();
    }
}
