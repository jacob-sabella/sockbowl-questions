package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.api.input.ImportPacketInput;
import com.soulsoftworks.sockbowlquestions.dto.ImportPacketResultDto;
import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.packetio.PacketExportFormat;
import com.soulsoftworks.sockbowlquestions.security.AuthenticatedUser;
import com.soulsoftworks.sockbowlquestions.service.PacketImportService;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Controller;

/**
 * Thin GraphQL layer for plaintext import, clone and export (D5, plan 3.1.8). All
 * parsing, taxonomy resolution and writes live in {@link PacketImportService}.
 *
 * <p>{@code exportPacket} has no {@code @PreAuthorize}: its NOT_FOUND/FORBIDDEN rule is
 * entirely the service's visibility check (plan 3.1.11's auth table), the same as
 * {@code getPacketById} has none and relies on {@code PacketReadPolicy} instead.
 */
@Controller
public class PacketImportController {

    private final PacketImportService packetImportService;

    public PacketImportController(PacketImportService packetImportService) {
        this.packetImportService = packetImportService;
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public ImportPacketResultDto importPacket(@Argument ImportPacketInput input, @AuthenticationPrincipal Jwt jwt) {
        return packetImportService.importPacket(input, AuthenticatedUser.fromJwt(jwt));
    }

    @MutationMapping
    @PreAuthorize("hasAuthority('packet:create')")
    public Packet clonePacket(@Argument String id, @Argument String name, @AuthenticationPrincipal Jwt jwt) {
        return packetImportService.clonePacket(id, name, AuthenticatedUser.fromJwt(jwt));
    }

    @QueryMapping
    public String exportPacket(@Argument String id, @Argument PacketExportFormat format) {
        return packetImportService.exportPacket(id, format);
    }
}
