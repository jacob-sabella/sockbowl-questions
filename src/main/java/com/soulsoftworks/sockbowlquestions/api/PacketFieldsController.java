package com.soulsoftworks.sockbowlquestions.api;

import com.soulsoftworks.sockbowlquestions.models.nodes.Packet;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator;
import com.soulsoftworks.sockbowlquestions.service.PacketValidator.PacketValidation;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.stereotype.Controller;

/**
 * M3 fields on the GraphQL {@code Packet} type (plan 3.1.3, 3.1.5). Both resolve from the
 * packet already fetched by the parent query or mutation, so neither adds a database
 * round trip, and both see exactly what the caller was allowed to see (a redacted
 * projection validates the same, since validation never reads answers).
 */
@Controller
public class PacketFieldsController {

    private final PacketValidator validator;

    public PacketFieldsController(PacketValidator validator) {
        this.validator = validator;
    }

    /** Optimistic-lock version to send back as {@code expectedVersion}; null in storage reads as 0. */
    @SchemaMapping(typeName = "Packet", field = "version")
    public int version(Packet packet) {
        return packet.getVersion() == null ? 0 : Math.toIntExact(packet.getVersion());
    }

    /** Playability and D7 warnings (PB-11). */
    @SchemaMapping(typeName = "Packet", field = "validation")
    public PacketValidation validation(Packet packet) {
        return validator.validate(packet);
    }
}
