package com.soulsoftworks.sockbowlquestions.security;

import com.soulsoftworks.sockbowlquestions.config.SecurityConfig;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.repository.PacketRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MockMvc slice proving the combined {@code hasAuthority(...) and
 * @packetAuthorizationService.canManage(...)} SpEL pattern used throughout
 * {@code PacketAuthoringController} — the {@code packetAuthorizationService} bean is
 * only ever consulted when {@code @EnableMethodSecurity} is active, so this is the
 * one place that pattern is proven end to end without booting Neo4j.
 */
@WebMvcTest(controllers = PacketAuthorizationProbeController.class, properties = "sockbowl.auth.enabled=true")
@Import({SecurityConfig.class, PacketAuthorizationService.class})
class PacketAuthorizationTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private PacketRepository packetRepository;

    private Packet packetOwnedBy(String ownerId) {
        Packet p = new Packet();
        p.setId("p1");
        p.setOwnerId(ownerId);
        return p;
    }

    @Test
    void ownerEditingOwnPacket_succeeds() throws Exception {
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        mvc.perform(get("/probe/manage/p1").with(jwt()
                        .jwt(j -> j.subject("sub-A"))
                        .authorities(new SimpleGrantedAuthority("packet:update"))))
                .andExpect(status().isOk());
    }

    @Test
    void nonOwnerAuthor_isDenied() throws Exception {
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        mvc.perform(get("/probe/manage/p1").with(jwt()
                        .jwt(j -> j.subject("sub-B"))
                        .authorities(new SimpleGrantedAuthority("packet:update"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void manageAnyAuthority_editsAnyPacket() throws Exception {
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy("sub-A")));

        // The coarse hasAuthority('packet:update') gate is independent of ownership
        // and is required on every mutation (see PacketAuthoringController) — a
        // manage-any user still needs the base action authority. packet:manage-any
        // only bypasses the *ownership* check inside canManage(...), letting sub-C
        // (who does not own p1) through where an ordinary author would be denied.
        mvc.perform(get("/probe/manage/p1").with(jwt()
                        .jwt(j -> j.subject("sub-C"))
                        .authorities(new SimpleGrantedAuthority("packet:update"),
                                new SimpleGrantedAuthority("packet:manage-any"))))
                .andExpect(status().isOk());
    }

    @Test
    void grandfatheredOwnerlessPacket_succeeds() throws Exception {
        when(packetRepository.findById("p1")).thenReturn(Optional.of(packetOwnedBy(null)));

        mvc.perform(get("/probe/manage/p1").with(jwt()
                        .jwt(j -> j.subject("sub-B"))
                        .authorities(new SimpleGrantedAuthority("packet:update"))))
                .andExpect(status().isOk());
    }

    @Test
    void missingCoarseAuthority_isDeniedBeforeOwnershipCheck() throws Exception {
        // Only packet:read — the coarse hasAuthority('packet:update') gate rejects
        // this before @packetAuthorizationService.canManage is ever consulted.
        mvc.perform(get("/probe/manage/p1").with(jwt()
                        .jwt(j -> j.subject("sub-A"))
                        .authorities(new SimpleGrantedAuthority("packet:read"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void guestNoToken_isDenied() throws Exception {
        // Same anonymous-AccessDenied -> AuthenticationEntryPoint trade as
        // SecurityConfigTest#unauthenticated_is_401: a guest with no bearer token
        // is rejected by @PreAuthorize but surfaces as 401, not 403.
        mvc.perform(get("/probe/manage/p1")).andExpect(status().isUnauthorized());
    }
}
