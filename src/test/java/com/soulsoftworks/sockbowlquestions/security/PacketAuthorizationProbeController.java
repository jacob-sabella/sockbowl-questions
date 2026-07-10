package com.soulsoftworks.sockbowlquestions.security;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Throwaway controller used only by {@link PacketAuthorizationTest} to exercise the
 * combined {@code hasAuthority(...) and @packetAuthorizationService.canManage(...)}
 * SpEL pattern used throughout {@code PacketAuthoringController}, via a
 * {@code @WebMvcTest} slice. Must be top-level (see {@code SecurityProbeController}
 * javadoc for why).
 */
@RestController
public class PacketAuthorizationProbeController {

    @GetMapping("/probe/manage/{packetId}")
    @PreAuthorize("hasAuthority('packet:update') and @packetAuthorizationService.canManage(#packetId)")
    public String manage(@PathVariable String packetId) {
        return "ok";
    }
}
